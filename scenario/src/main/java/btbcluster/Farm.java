package btbcluster;

import broadwick.BroadwickException;
import broadwick.stochastic.SimulationEvent;
import broadwick.statistics.distributions.IntegerDistribution;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 */
@Slf4j
public class Farm implements Serializable {

    public Farm(final String id, final ProjectSettings settings) {
        this(id, ((int)settings.getGenerator().getGaussian(120, 40)), null, null, settings);
    }

    public Farm(final String id, final int size, final Double xLocation, final Double yLocation, final ProjectSettings settings) {
        this.id = id;
        this.herdSize = size;
        this.xLocation = xLocation;
        this.yLocation = yLocation;
        this.setts = new ArrayList<>();
        this.infectedCows = new ArrayList<>();
        this.settings = settings;
        this.offMovementDistribution = new IntegerDistribution();
        datesOfSlaughterhouseMoves = new ArrayList<>();

        // NOTE - the last positive test, clear test and numClearTests need to be initialised.
        this.restricted = false;
        this.lastClearTestDate = -1;
        this.lastPositiveTestDate = -1;
        this.numClearTests = -1;
        this.nextWHTDate = -1;
    }

    /**
     * Set the date of the last positive test, and reset the number of clear tests.
     *
     * @param date the [Broadwick] date of the positive test.
     */
    public void setLastPositiveTestDate(final int date) {
        lastPositiveTestDate = date;
        numClearTests = 0;
        nextWHTDate = date + 60;
        restricted = true;
    }

    /**
     * Set the date of the last lear test, and update the number of clear tests.
     * @param date the [Broadwick] date of the positive test.
     */
    public void addClearTest(final int date) {
        lastClearTestDate = date;

        if (numClearTests == -1 || numClearTests >= 2) {
            // either has a clear WHT and was already cleared or this is the second
            numClearTests = -1;
            nextWHTDate = date + (365 * settings.getTestIntervalInYears());
            restricted = false;
        } else {
            //if (numClearTests < 2) {
            numClearTests += 1;
            nextWHTDate = date + 60;
            restricted = true;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        setts.clear();
    }

    /**
     * Create the elements of the kernel for this herd.
     * @param date        the date the kernel is created.
     */
    public Map<SimulationEvent, Double>  createTransitionKernel(final int date) {
        // TODO - return a map of <SimulationEvent, rate> that can be iterated over un updateKernel
        log.trace("Generating kernel for farm {}", id);
        final Map<SimulationEvent, Double> kernel = new HashMap<>();

        for (InfectedCow cow : infectedCows) {
            InfectedCow finalState = cow.copyOf();
            switch (cow.getInfectionStatus()) {
                case EXPOSED:
                    // Add E->T event
                    finalState.setInfectionStatus(InfectionState.TESTSENSITIVE);
                    kernel.put(new ScenarioTransmissionEvent(cow, finalState, this), settings.getSigma());
                    break;
                case TESTSENSITIVE:
                    // Add T->I event
                    finalState.setInfectionStatus(InfectionState.INFECTIOUS);
                    kernel.put(new ScenarioTransmissionEvent(cow, finalState, this), settings.getGamma());
                    break;
                case INFECTIOUS:
                    // Add S->E event, since the newly infected cow does not have an id (we're not tracking
                    // susceptible animals) we will give it an empty one and let the event handler deal with it.
                    finalState = new InfectedCow("UNKNOWN", cow.getSnps(), cow.getLastSnpGeneration(),
                            InfectionState.EXPOSED);
                    kernel.put(new ScenarioTransmissionEvent(cow, finalState, this),
                            (herdSize - getInfectedCows().size())*settings.getBeta());

                    // TODO - sometimes I see an event cow:INFECTIOUS -> UNKNOWN:INFECTIOUS which should not be possible
                    if (settings.isReservoirsIncluded()) {
                        // Cattle -> badger transmission
                        for (Sett sett : setts) {
                            // since the newly infected badger does not have an id (we're not tracking
                            // susceptible animals) we will give it an empty one and let the event handler deal with it.
                            InfectedBadger infectedBadger = new InfectedBadger(sett.getId(), cow.getSnps(),
                                    cow.getLastSnpGeneration(), date);
                            kernel.put(new ScenarioTransmissionEvent(cow, infectedBadger, this),
                                   settings.getAlphaPrime());
                        }
                    }
                    break;
            }
        }
        if (settings.isReservoirsIncluded()) {
            for (Sett sett : setts) {
                for (InfectedBadger badger : sett.getInfectedBadgers()) {
                    // this badger can pass infection to all cows in the herd that are susceptible.
                    // that is herdSize-infectedCows.size() cows.
                    InfectedCow finalState = new InfectedCow("UNKNOWN_" + id, badger.getSnps(), badger.getLastSnpGeneration(),
                            InfectionState.EXPOSED);

                    kernel.put(new ScenarioTransmissionEvent(badger, finalState, this),
                            (herdSize - getInfectedCows().size())*settings.getAlpha());
                }
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Generated kernel for farm {} as {}", id, kernel.toString());
        }

        return kernel;
    }

    /**
     * Select n infected cows at random.
     *
     * @param n the number of infected cows to select.
     */
    public Collection<InfectedCow> selectInfectedCows(final int n) {
        // assert n <= infectedCows.size()
        return settings.getGenerator().selectManyOf(infectedCows, n);
    }

    /**
     * Perform a WHT on the herd, returning a list of reactors. For each infected cow we declare them as a reactor
     * if the sensitivity is greater than a random number (which is supplied as a list to the method).
     *
     * @param date        the date of the WHT
     * @param rn          a list of uniformly distributed random numbers (one for each infected cow in the herd).
     * @param sensitivity the sensitivity of the test.
     */
    public Collection<InfectedCow> performWHT(final int date, final Collection<Double> rn, final double sensitivity) {
        log.trace("Performing WHT on {} at {}", id, date);
        // assert rn.size() == infectedCows.size()
        // for each infected cow in the herd add them to a list of reactors if rn[i] < sensitivity.
        // if a cow is a reactor then we set the detected date for the cow.
        List<InfectedCow> reactors = new ArrayList<>();
        if (infectedCows.size() > 0) {
            final Iterator<Double> iterator = rn.iterator();
            for (final InfectedCow cow : infectedCows) {
                log.trace("Checking animal {}", cow.getId());

                if (isTestedCowPositive(cow, date, iterator.next(), sensitivity)) {
                    reactors.add(cow);
                }
            }
            infectedCows.removeAll(reactors);
        }
        if (log.isTraceEnabled()) {
            log.trace("Performed WHT and found {} reactors. {}", reactors.size(), reactors.stream().map(InfectedCow::getId).collect(Collectors.toList()));
        }

        // Set the date of the next WHT and the dates of the last clear and failed test.
        if (reactors.size() > 0) {
            settings.getResults().recordReactors(reactors.size());
            setLastPositiveTestDate(date);
        } else {
            addClearTest(date);
        }
        return reactors;
    }

    public boolean isTestedCowPositive(final InfectedCow cow, final int date, final double randNo, final double sensitivity) {
        if (settings.getDetectableStates().contains(cow.getInfectionStatus()) && randNo < sensitivity) {
            // cow is detected, save sample, cull, and place farm under movement restriction.
            cow.setDateSampleTaken(date);
            settings.generateSnps(date, cow.getLastSnpGeneration());
            cow.getSnps().addAll(settings.generateSnps(date, cow.getLastSnpGeneration()));
            cow.setLastSnpGeneration(date);
            // TODO save culled cow to list (possibly in the scenario class)
            settings.getResults().getInfectedCows().remove(cow);
            log.trace(String.format("Cow %s tested positive on %d", cow, date));
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        return ((Farm) obj).getId().equals(id);
    }

    /**
     * Create a json object of this class.
     *
     * @return a json formatted string.
     */
    public String asJson() {
        String jsonStr = "";

        try {
            ObjectMapper mapper = new ObjectMapper();
            jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (JsonProcessingException jpe) {
            throw new BroadwickException("Error saving arm as json; " + jpe.getLocalizedMessage());
        }
        return jsonStr;
    }

    @Getter
    private final String id;
    @Getter
    @Setter
    private int herdSize;
    @Getter
    private final Double xLocation, yLocation;
    @Getter
    @Setter
    private int lastClearTestDate;
    @Getter
    private int lastPositiveTestDate;
    @Getter
    @Setter
    private int numClearTests;
    @Getter
    @Setter
    private int nextWHTDate;
    @JsonIdentityInfo(generator= ObjectIdGenerators.PropertyGenerator.class, property="id")
    @JsonIdentityReference(alwaysAsId=true)
    @Getter
    private Collection<Sett> setts;
    @JsonIdentityInfo(generator= ObjectIdGenerators.PropertyGenerator.class, property="id")
    @JsonIdentityReference(alwaysAsId=true)
    @Getter
    private Collection<InfectedCow> infectedCows;
    @Getter
    private Collection<Integer> datesOfSlaughterhouseMoves;
    @Getter
    @Setter
    private boolean restricted;
    @Getter
    private final IntegerDistribution offMovementDistribution;
    private ProjectSettings settings;
    private static final long serialVersionUID = 201903290000000001L;
}
