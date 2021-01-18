package btbcluster;

import broadwick.BroadwickException;
import broadwick.stochastic.AmountManager;
import broadwick.stochastic.SimulationEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

/**
 * Create the amount manager that stochastically creates initial infection states.
 */
@Slf4j
public class MyAmountManager implements AmountManager {

    public MyAmountManager(final ProjectSettings settings, final NIBtbClusterScenario scenario) {
        this.settings = settings;
        this.scenario = scenario;
    }

    @Override
    public void performEvent(SimulationEvent event, int times) {

        ScenarioTransmissionEvent thisEvent = (ScenarioTransmissionEvent) event;
        Farm thisFarm = thisEvent.getFarm();

        log.debug( "Performing event {} {} times", thisEvent.toString(), times );
        final int date = ((int) scenario.getSimulator().getCurrentTime());

        // No need to do this 'times' as the rates we included already takes this into account.
        // if we have an E->T or T_>I event, it will only be done once anyway
        //for (int i = 0; i < times; i++) {

        if (thisEvent.getInitialState() instanceof InfectedCow) {
            final InfectedCow source = (InfectedCow) thisEvent.getInitialState();

            // This event may not be possible as the infected animal may have been removed via a RWHT.
            if (thisFarm.getInfectedCows().contains( source )) {
                // update the SNPs in the cow.
                source.getSnps().addAll( settings.generateSnps( date, source.getLastSnpGeneration() ) );
                source.setLastSnpGeneration( date );

                if (thisEvent.getFinalState() instanceof InfectedCow) {
                    // Cow->Cow transmission
                    InfectedCow finalState = ((InfectedCow) thisEvent.getFinalState());
                    if (finalState.getId().equals( "UNKNOWN" )) {
                        // This is a new infection so create a new cow.
                        final InfectedCow cow = new InfectedCow( String.format( "Cow_%05d", settings.getNextCowId() ),
                                source.getSnps(),
                                source.getLastSnpGeneration(),
                                ((InfectedCow) event.getFinalState()).getInfectionStatus() );
                        thisFarm.getInfectedCows().add( cow );
                        scenario.getResults().getInfectedCows().add( cow );
                        // record a cow-cow transmission
                        scenario.getResults().recordCowCowTransmission();
                        settings.getResults().getInfectionTree().insert( source, cow );
                    } else {
                        source.setInfectionStatus( finalState.getInfectionStatus() );
                    }
                } else if (event.getFinalState() instanceof InfectedBadger) {
                    // Cow->Badger transmission. Create a new badger and add it to a random sett attached to the farm.
                    InfectedBadger badger = new InfectedBadger( String.format( "Badger_%05d", settings.getNextBadgerId() ),
                            source.getSnps(), source.getLastSnpGeneration(), date);
                    String settId = ((InfectedBadger) event.getFinalState()).getId();
                    Sett sett = thisFarm.getSetts().stream().filter( s -> s.getId().equals( settId ) ).findFirst().get();
                    sett.getInfectedBadgers().add( badger );

                    // record a cow-badger transmission
                    scenario.getResults().recordCowBadgerTransmission();
                    settings.getResults().getInfectionTree().insert( source, badger );
                } else {
                    throw new BroadwickException( "Could not recognise event:" + event.toString() );
                }
            } else {
                log.trace( "WARNING - farm {} does not contain cow, {}", thisFarm.getId(), thisFarm.getInfectedCows() );
            }
        } else if (thisEvent.getInitialState() instanceof InfectedBadger) {
            final InfectedBadger source = (InfectedBadger) thisEvent.getInitialState();

            Set<Integer> snps = new HashSet<>();
            if (settings.getDiversityModel().equals( "MAXIMUM" )) {
                // Maximum diversity => the snps comes from ALL the badgers in the sett (maximum mixing).
                for (Sett sett : thisFarm.getSetts()) {
                    for (InfectedBadger badger : sett.getInfectedBadgers()) {
                        badger.getSnps().addAll( settings.generateSnps( date, badger.getLastSnpGeneration() ) );
                        badger.setLastSnpGeneration( date );
                        snps.addAll( badger.getSnps() );
                    }
                }
            } else if (settings.getDiversityModel().equals( "MINIMUM" )) {
                // The SNPs are just those that the badger was infected with and the badger does not
                // generate new SNPs.
                snps.addAll( source.getSnps() );
            } else if (settings.getDiversityModel().equals( "INTERMEDIATE" )) {
                // We have intermediate diversity => the diversity is generated from the one badger
                // that was infected originally with a given mutation rate.
                source.getSnps().addAll( settings.generateSnps( date, source.getLastSnpGeneration() ) );
                source.setLastSnpGeneration( date );
                snps.addAll( source.getSnps() );
            }
            if (event.getFinalState() instanceof InfectedCow) {
                // badger->cow transmission
                // create an InfectedCow object on this farm and add it to the infectedcows collection.
                InfectedCow cow = new InfectedCow( String.format( "Cow_%05d", settings.getNextCowId() ), snps,
                        date,
                        InfectionState.EXPOSED );
                thisFarm.getInfectedCows().add( cow );
                scenario.getResults().getInfectedCows().add( cow );

                // record a badger-cow transmission
                scenario.getResults().recordBadgerCowTransmission();
                settings.getResults().getInfectionTree().insert( source, cow );
                log.debug( "Simulated {} -> {}", source.getId(), cow.getId() );
            } else if (event.getFinalState() instanceof InfectedBadger) {
                final InfectedBadger destination = (InfectedBadger) thisEvent.getFinalState();
                if (source.getId() == destination.getId()) {
                    // This is the same badger - this indicates that the badger is dead and should be removed.
                    for (Sett sett : ((ScenarioTransmissionEvent) event).getFarm().getSetts()) {
                        if (sett.getInfectedBadgers().contains( source )) {
                            // remove it!
                            sett.getInfectedBadgers().remove( source );

                            break;
                        }
                    }

                } else {
                    // badger->badger transmission
                    // TODO - badger->badger transmission is not implemented in the simulation.
                }
            } else {
                throw new BroadwickException( "Could not recognise event:" + event.toString() );
            }
        } else {
            throw new BroadwickException( "Unknown event " + thisEvent );
        }
        //} // for (int i = 0; i < times; i++) {
    }

    @Override
    public String toVerboseString() {
        return "";
    }

    @Override
    public void resetAmount() {
        // not required
    }

    @Override
    public void save() {
        // not required
    }

    @Override
    public void rollback() {
        // not required
    }

    private ProjectSettings settings;
    private NIBtbClusterScenario scenario;
    private static final long serialVersionUID = 201903290000000001L;
}
