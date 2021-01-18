package btbcluster;

import broadwick.BroadwickException;
import broadwick.data.Test;
import broadwick.stochastic.Observer;
import broadwick.stochastic.SimulationEvent;
import broadwick.stochastic.StochasticSimulator;
import broadwick.statistics.distributions.HypergeometricDistribution;
import broadwick.utils.Pair;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public class ScenarioObserver extends Observer {

    public ScenarioObserver(StochasticSimulator simulator,
                            NIBtbClusterScenario scenario,
                            ProjectSettings settings) {
        super( simulator );
        this.scenario = scenario;
        this.settings = settings;
        this.numMovementsForPeriod = (settings.getNumMovements() * settings.getStepSize())
                / (settings.getEndDate() - settings.getStartDate());
    }


    @Override
    public void started() {
        log.debug( "Started observer at {}", getProcess().getCurrentTime() );
    }

    @Override
    public void step() {
        final double currentTime = getProcess().getCurrentTime();
        log.debug( "Observing step at time {}", currentTime );

        // Register thetas
        registerThetaEvents( currentTime );

        // Perform the movements/births/deaths for this step.
        doMovements();

        // update transitions
        scenario.updateKernel();
    }

    @Override
    public void finished() {
        log.debug( "Finished observer at {}", getProcess().getCurrentTime() );
    }

    @Override
    public void theta(double time, Collection<Object> events) {
        log.debug( "Observing {} tests at {} ", events.size(), time );
        boolean failedTests = false;

        for (final Object event : events) {
            // We should only have test events.....
            if (event.getClass() == Test.class) {
                final Test testEvent = (Test) event;
                Farm farm = scenario.getFarms().get( testEvent.getLocation() );
                final List<Double> rands = DoubleStream.generate( () -> settings.getGenerator().getDouble() )
                        .limit( farm.getInfectedCows().size() )
                        .boxed().collect( Collectors.toList() );

                final Collection<InfectedCow> reactors = farm.performWHT( testEvent.getTestDate(), rands, settings.getTestSensitivity() );
                if (reactors.isEmpty()) {
                    failedTests = true;
                }
            }
        }

        if (failedTests) {
            scenario.updateKernel();
        }
    }

    @Override
    public void observeEvent(SimulationEvent event, double tau, int times) {
//        log.debug("observing event {} ", event);
    }

    public void registerThetaEvents(final double time) {
        log.debug( "Registering theta events time {}", time );
        for (Farm farm : scenario.getFarms().values()) {
            final int nextWHT = farm.getNextWHTDate();
            log.trace( "   next theta events time {} on farm {}", nextWHT, farm.getId() );
            if ((nextWHT >= time) && (nextWHT < (time + settings.getStepSize()))) {
                log.trace( "   registering WHT at {} on {}", nextWHT, farm.getId() );
                getProcess().registerNewTheta( this, nextWHT, new Test( "WHT", farm.getId(), farm.getId(),
                        nextWHT, null, null ) );
            }
        }
    }

    /**
     * Preform the movements for this period. The algorithm is that we randomly pick a movement (which contains a
     * departure-destination farm) farm from which we move animals, if this is not a restricted herd and if it has
     * infected animals we select a subset of that herd to pretest and move.
     */
    private void doMovements() {
        log.debug( "Moving {} animals in period.", numMovementsForPeriod );
        final StopWatch sw = new StopWatch();
        sw.start();

        // The algorithm is to loop through each farm (at random) and for each sample from the off movement distribution
        // to get the number of off moves for the period.
        // Then we loop over the movements (again at random) to select the destination farm and the number of animals
        // moved to that farm, then premeovement test each animal and deal with the consequences of a positive test.
        int numMovedSoFar = 0;
        int infectedAnimalsMoved = 0;
        final int numKnownFarmFarmMoves = settings.getMovementFrequencies().size();
        while (numMovedSoFar < numMovementsForPeriod) {

            // Find a movement at random between 2 farms, according to the known farm-farm movement distribution.
            int rnd = settings.getGenerator().getInteger( 0, numKnownFarmFarmMoves - 1 );
            Pair<String, String> movementData = settings.getMovementFrequencies().get( rnd );

            // set up the movement if (and only if) neither farm is under movement restriction and there are infected
            // animals on the first (departing) farm [we don't track non-infecteds]
            Farm departureFarm = scenario.getFarms().get( movementData.getFirst() );
            Farm destinationFarm = scenario.getFarms().get( movementData.getSecond() );
            if (departureFarm == null || destinationFarm == null) {
                log.error( "Could not find farm (departure = {}, destination={})", departureFarm, destinationFarm );
                throw new BroadwickException( "Could not determine farm movements" );
            }
            if (!departureFarm.isRestricted() && !destinationFarm.isRestricted()) {

                int numAnimalsToBeMoved = 0;
                if (departureFarm.getOffMovementDistribution().getNumBins() > 0) {
                    numAnimalsToBeMoved = departureFarm.getOffMovementDistribution().getRandomBin();
                    // before we move animals make sure there are enough animals on the departure farm
                    // if (numAnimalsToBeMoved > departureFarm.getHerdSize()) numAnimalsToBeMoved = 0;
                    // but we are keeping the herd size (approximately) constant and so are not really interested in
                    // tracking the movement of animals unless they are infected.
                }

                if (numAnimalsToBeMoved > 0) {
                    // For each of these animalsToBeMoved, pre-movement test them.
                    // We assume that the animals to be moved are totally random and follows a hypergeometric distribution.
                    // If any fail the test, we cull them and put the herd under restriction, else we move
                    // animals.
                    Collection<InfectedCow> infectedCowsInHerd = departureFarm.getInfectedCows();
                    log.trace( "off movement dist for farm {} = {}", departureFarm.getId(), departureFarm.getOffMovementDistribution().toCsv() );

                    int numSuccesses = infectedCowsInHerd.size();
                    departureFarm.setHerdSize( Math.max( departureFarm.getHerdSize(), numAnimalsToBeMoved ) );
                    departureFarm.setHerdSize( Math.max( departureFarm.getHerdSize(), numSuccesses ) );
                    final int numInfectedAnimalsToBeMoved = new HypergeometricDistribution( departureFarm.getHerdSize(),
                            numAnimalsToBeMoved, numSuccesses ).sample();

                    if (numInfectedAnimalsToBeMoved == 0) {
                        log.trace( "{}", String.format( "Moving %d (0 infected) animals from farm %s (N=%d, Infections=%d) to farm %s",
                                numAnimalsToBeMoved, departureFarm.getId(), departureFarm.getHerdSize(), numSuccesses, destinationFarm.getId() ) );
                    } else {
                        log.debug( "{}", String.format( "Moving %d (%d infected) animals from farm %s (N=%d, Infections=%d) to farm %s",
                                numAnimalsToBeMoved, numInfectedAnimalsToBeMoved, departureFarm.getId(),
                                departureFarm.getHerdSize(), numSuccesses, destinationFarm.getId() ) );

                        Collection<InfectedCow> infectedAnimalsToBeMoved = departureFarm.selectInfectedCows( numInfectedAnimalsToBeMoved );
                        int numDetectedPreMoves = 0;
                        int time = ((int) getProcess().getCurrentTime());

                        for (InfectedCow cow : infectedAnimalsToBeMoved) {
                            if (departureFarm.isTestedCowPositive( cow, time, settings.getGenerator().getDouble(),
                                    settings.getTestSensitivity() )) {
                                departureFarm.getInfectedCows().remove( cow );
                                ++numDetectedPreMoves;
                            }
                        }

                        if (numDetectedPreMoves > 0) {
                            // If I detected any cow they will have been culled; now put the herd under restriction.
                            departureFarm.setLastPositiveTestDate( time );
                            log.trace( "{}", String.format( "Putting herd %s under movement restriction", departureFarm.getId() ) );
                            // No animals are moved off this farm.
                            numAnimalsToBeMoved = 0;
                        } else {
                            // No animals detected, now move the 'numInfectedAnimalsToBeMoved' infected animals.
                            if (infectedAnimalsToBeMoved.isEmpty()) {
                                log.trace( "{}", String.format( "Moving %d [clear] animals from %s to %s", numAnimalsToBeMoved,
                                        departureFarm.getId(),
                                        destinationFarm.getId() ) );
                            } else {
                                // moving an infected cow.
                                log.trace( "{}", String.format( "Moving %d (%d undetected) from %s to %s",
                                        numAnimalsToBeMoved, infectedAnimalsToBeMoved.size(), departureFarm.getId(), destinationFarm.getId() ) );
                                for (InfectedCow cow : infectedAnimalsToBeMoved) {
                                    if (departureFarm.getInfectedCows().remove( cow )) {
                                        destinationFarm.getInfectedCows().add( cow );
                                    }
                                }

                                final int destinationFarmSize = destinationFarm.getHerdSize();

                                // we have moved unobserved infections - we may need to update the herd size (in case it
                                // causes an exception in HypergeometricDistribution.sample()
                                if (destinationFarm.getInfectedCows().size() > destinationFarm.getHerdSize()) {
                                    destinationFarm.setHerdSize( destinationFarm.getInfectedCows().size() );
                                }
                            }

                            infectedAnimalsMoved += infectedAnimalsToBeMoved.size();
                        }
                    }
                }
                numMovedSoFar += numAnimalsToBeMoved;
            }
        }

        log.debug( "Moved {} animals in period (time taken = {})", numMovedSoFar, sw );
        log.debug( "Moved {}/{} infected animals in period", infectedAnimalsMoved, scenario.getResults().getInfectedCows().size() );

        settings.getResults().recordNumInfectedAnimalsMoved( infectedAnimalsMoved );

        doSlaughterhouseMoves();
    }

    /**
     * Perform slaughterhouse movements from the distribution of moves to slaughter.
     */
    private void doSlaughterhouseMoves() {
        final int date = ((int) getProcess().getCurrentTime());
        final StopWatch sw = new StopWatch();
        sw.start();

        // get the list of farms that have sent animals to slaughter in this period (this
        // is run at the end of the period).
        final List<Integer> datesRange = IntStream.range( date - settings.getStepSize(), date )
                .boxed().collect( Collectors.<Integer>toList() );
        final List<Farm> farmsMovingAnimals = scenario.getFarms().values().stream()
                .filter( farm -> (farm.getDatesOfSlaughterhouseMoves().stream()
                        .filter( d -> datesRange.contains( d.intValue() ) )
                        .count() > 0) )
                .collect( Collectors.toList() );

        if (farmsMovingAnimals.isEmpty()) {
            log.debug( "No moves to slaughter in period {}-{}", date - settings.getStepSize(), date );
            return;
        }

        final int movesForPeriod = ((int) (1.0 * settings.getNumSlaughters() * settings.getStepSize()) / (settings.getEndDate() - settings.getStartDate()));
        if (log.isTraceEnabled()) {
            log.trace( "{}", String.format( "moving %d animals to slaughter in period %d-%d from %d farms",
                    movesForPeriod, date - settings.getStepSize(), date, farmsMovingAnimals.size() ) );
        }

        // TODO: Perhaps we may be better off removing animals from random farms rather than replaying exact slaughterhouse moves...
        int numUndetectedAtSlaughter = 0;
        int numDetectedAtSlaughter = 0;
        int numAnimalsMoved = 0;
        Collections.shuffle( farmsMovingAnimals );
        for (final Farm farm : farmsMovingAnimals) {
            final int numInfectedCowsOnFarm = farm.getInfectedCows().size();
            int herdSize = farm.getHerdSize();

            // how many moves do we need off this farm?
            final int numAnimalsToMoveOffFarm = settings.getGenerator().getInteger( 1, ((int) Math.ceil( (1.0*movesForPeriod) / farmsMovingAnimals.size() )) );

            log.trace( "{}", String.format( "Slaughtering %d animals from farm %s (size=%d, of which %d are infected)",
                    numAnimalsToMoveOffFarm, farm.getId(), herdSize, numInfectedCowsOnFarm ) );

            if (numAnimalsToMoveOffFarm > 0) {
                // We have more moves to slaughter than we have animals on the farm - update the number of animals on the farm.
                if (numAnimalsToMoveOffFarm > herdSize) {
                    herdSize = numAnimalsToMoveOffFarm;
                    farm.setHerdSize( herdSize );
                }

                // Select the number of infected animals from the farm to move to slaughter.
                // if we have more infections than animals on the farm, update the size of the herd.
                final int numInfectedAnimalsForRemoval = new HypergeometricDistribution( herdSize,
                        numAnimalsToMoveOffFarm,
                        numInfectedCowsOnFarm ).sample();

                // select these animals from the farm
                final Collection<InfectedCow> animalsForSlaughter = farm.selectInfectedCows( numInfectedAnimalsForRemoval );

                // test every animal as they are all tested at slaughter
                for (InfectedCow cow : animalsForSlaughter) {

                    if (farm.isTestedCowPositive( cow, date, settings.getGenerator().getDouble(), settings.getTestSensitivity() )) {
                        numDetectedAtSlaughter++;

                        // set the date of the last positive test for the herd (this sets the number of clear tests,
                        // places the herd under restriction and sets the date of the next WHT for the herd).
                        farm.setLastPositiveTestDate( date );

                        // finally remove the cow from the herd.
                        farm.getInfectedCows().remove( cow );
                        log.trace( "Animal detected at slaughter on farm {}. Next WHT at {}", farm.getId(), farm.getNextWHTDate() );
                    } else {
                        numUndetectedAtSlaughter++;
                    }
                }
            }
            numAnimalsMoved += numAnimalsToMoveOffFarm;
            if (numAnimalsMoved > movesForPeriod) {
                break;
            }
        }

        log.debug( "{}", String.format( "Removed %d reactors, time taken=%s", numDetectedAtSlaughter, sw ) );
        settings.getResults().recordNumberOfDetectedAnimalsAtSlaughter( numDetectedAtSlaughter );
        settings.getResults().recordNumberOfUndetectedAnimalsAtSlaughter( numUndetectedAtSlaughter );
    }

    private NIBtbClusterScenario scenario;
    private ProjectSettings settings;
    private final int numMovementsForPeriod;
    private static final long serialVersionUID = 201903290000000001L;
}


