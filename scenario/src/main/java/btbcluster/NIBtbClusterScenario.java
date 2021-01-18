package btbcluster;

import broadwick.BroadwickConstants;
import broadwick.BroadwickException;
import broadwick.LoggingFacade;
import broadwick.io.FileInput;
import broadwick.io.FileInputIterator;
import broadwick.math.Factorial;
import broadwick.rng.RNG;
import broadwick.statistics.distributions.IntegerDistribution;
import broadwick.statistics.distributions.MultinomialDistribution;
import broadwick.stochastic.SimulationController;
import broadwick.stochastic.SimulationEvent;
import broadwick.stochastic.StochasticSimulator;
import broadwick.stochastic.TransitionKernel;
import broadwick.utils.Pair;
import broadwick.stochastic.algorithms.TauLeapingFixedStep;
import ch.qos.logback.classic.Level;
import com.google.common.base.Throwables;
import lombok.Getter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

/**
 * Run a single simulation of the spread of bovine TB within a cluster of farms.
 */
public class NIBtbClusterScenario {

    /**
     * Create the application, setting up the logging.
     *
     * @param cli the command line arguments.
     */
    public NIBtbClusterScenario(final CommandLineOptions cli) {

        //Set up the logger
        final String logConsoleFormatMsg = "[%thread] %-5level %msg %n";
        final String logFileFormatMsg = "%d{HH:mm:ss.SSS} %-5level %class.%line %msg %n";
        final LoggingFacade logFacade = new LoggingFacade();
        log = logFacade.getRootLogger();
        try {
            // The valid levels are INFO, DEBUG, TRACE, ERROR, ALL
            final String logFile = String.format( "NIBtbClusterScenario_%s.log", cli.getScenarioId() );
            logFacade.addFileLogger( logFile, cli.getLoggingLevel(), logFileFormatMsg, true );
            logFacade.addConsoleLogger( "INFO", logConsoleFormatMsg );

            logFacade.getRootLogger().setLevel( Level.TRACE );
        } catch (BroadwickException ex) {
            log.error( "{}\nSomething went wrong starting project. See the error messages.",
                    ex.getLocalizedMessage() );
        }
    }

    /**
     * Initialise the application.
     */
    private final void init(final String id) {
        try {
            log.info( "Initialising project from file [configurations: {}] and [parameters: {}]", configFile, parametersFile );
            results = new ScenarioResults( id );
            settings = new ProjectSettings( id, results, configFile, parametersFile );

            createFarms();
            createSetts();

            readSlaughterhouseMoves();
            readObservedSnpDistribution();
            readSamplingRatesPerYear();
            readMovementFrequencies();

            // Seed infection in some herds and mark some herds as bing under movement restriction.
            seedInfectedAnimals();
            markRestrictedHerds();
            //log.trace("Created farms : {}", farms.values().stream().map(Farm::asJson).collect(Collectors.joining(",")));
            //log.trace("Created setts : {}", setts.values().stream().map(Sett::asJson).collect(Collectors.joining(",")));

            // Create the kernel, simulator, observers and controller
            final TransitionKernel kernel = new TransitionKernel();
            final MyAmountManager amountManager = new MyAmountManager( settings, this );
            simulator = new TauLeapingFixedStep( amountManager, kernel, settings.getStepSize() );
            simulator.setRngSeed( settings.getGenerator().getInteger( 0, Integer.MAX_VALUE ) );
            simulator.setStartTime( settings.getStartDate() );

            // Add the observer
            simulator.getObservers().clear();
            final ScenarioObserver observer = new ScenarioObserver( simulator, this, settings );
            observer.registerThetaEvents( settings.getStartDate() );
            simulator.addObserver( observer );

            // Add a controller
            final SimulationController controller = new SimulationController() {
                @Override
                public boolean goOn(final StochasticSimulator simulator) {

                    final double currentTime = simulator.getCurrentTime();
                    final boolean isDateValid = currentTime <= settings.getEndDate()
                            && currentTime != Double.NEGATIVE_INFINITY
                            && currentTime != Double.POSITIVE_INFINITY;
                    final boolean hasMoreTransitions = !simulator.getTransitionKernel().getCDF().isEmpty();
                    final boolean isLargerThanMaxEpidemicSize = results.getInfectedCows().size() > settings.getMaxOutbreakSize();

                    // startDate is after (chronologically) the endDate and the scenario has not been rejected.
                    if (!isDateValid) {
                        log.debug( "Terminating simulation time = {}", currentTime );
                    }
                    if (!hasMoreTransitions) {
                        log.debug( "Terminating simulation (no more possible transitions)" );
                        // TODO finishedPrematurely = true;
                    }
                    if (isLargerThanMaxEpidemicSize) {
                        log.debug("Terminating simulation (maximum outbreak [{}] exceeds max size)", results.getInfectedCows().size());
                    }

                    if (!hasMoreTransitions) {
                        // TODO results.incrementOutbreakContainedCount();
                        log.debug("No more possible transitions");
                    }

                    log.trace( "Controller: go on = {}", isDateValid && !isLargerThanMaxEpidemicSize && hasMoreTransitions );
                    return isDateValid && !isLargerThanMaxEpidemicSize && hasMoreTransitions;
                }

                private static final long serialVersionUID = 201903290000000001L;
            };
            simulator.setController( controller );

            // finally create the transition kernel.
            updateKernel();
        } catch (BroadwickException ex) {
            throw new BroadwickException( ex );
        }
    }

    /**
     * Run the scenario.
     */
    public void run() {
        log.info( "Running scenario from {} - {}", settings.getStartDate(), settings.getEndDate() );
        final StopWatch sw = new StopWatch();
        sw.start();

        simulator.run();

        // Now sample from the infection tree; first get a list of infected cows from the infection tree
        // next group them by the year they were sampled and loop over each year, sampling the appropriate
        // numbers for each year.
        final Collection<InfectedCow> infectedCows = settings.getResults().getInfectionTree().getInfectedCows();

        final List<InfectedCow> sampled = infectedCows.stream()
                .filter( c -> c.getDateSampleTaken() != -1 ).collect( Collectors.toList() );
        log.info( "sampled cows = {}", sampled );

        final Map<Integer, List<InfectedCow>> groups = infectedCows.stream()
                .filter( c -> c.getDateSampleTaken() != -1 )
                .collect( groupingBy( c -> BroadwickConstants.toDate( c.getDateSampleTaken() ).year().get() ) );
        log.info( "Sampled Cows by year = {}", groups );

        Collection<InfectedCow> sampledCows = new ArrayList<>();
        if (!groups.isEmpty()) {
            sampledCows = new ArrayList<>();
            int attempt = 0;
            while (sampledCows.isEmpty() && attempt < 10) {
                for (Map.Entry<Integer, List<InfectedCow>> entry : groups.entrySet()) {
                    log.debug( "year - {} : sampling rate {}", entry.getKey(), settings.getSamplingRatesPerYear().get( entry.getKey() ) );
                    log.debug( "infected cows for year = {}", entry.getValue() );
                    double samplingRate = settings.getSamplingRatesPerYear().get( entry.getKey() );
                    int numSampled = (int) (entry.getValue().size() * samplingRate);
                    sampledCows.addAll( settings.getGenerator().selectManyOf( entry.getValue(), numSampled ) );
                }
                attempt += 1;
            }
            log.debug( "Infection network contains {} cows", infectedCows.size() );
            log.debug( "Sampled network contains {} cows", sampledCows.size() );
            results.recordNumberOfSamplesTaken( sampledCows.size() );
        }

        // Now calculate the pairwise distances.
        log.debug( "Calculating pairwise snp distances on {} vertices", sampledCows.size() );
        if (sampledCows.size() > 1) {
            for (InfectedCow nodeA : sampledCows) {
                for (InfectedCow nodeB : sampledCows) {
                    if (!nodeA.getId().equals( nodeB.getId() )) {
                        // TODO - check this is ok (not sure about !nodeB.getSnps().contains(snpA)))
                        int snpDiff = 0;
                        for (int snpA : nodeA.getSnps()) {
                            if (!nodeB.getSnps().contains( snpA )) {
                                snpDiff++;
                            }
                        }
                        for (int snpB : nodeB.getSnps()) {
                            if (!nodeA.getSnps().contains( snpB )) {
                                snpDiff++;
                            }
                        }
                        settings.getResults().getSnpDistanceDistribution().setFrequency( snpDiff );
                    }
                }
            }
        }
        log.debug( "Pairwise snp distances for {} vertices = {}", sampledCows.size(),
                settings.getResults().getSnpDistanceDistribution().toString() );

        // Calculate the log-likelihood for these results.
        results.setLoglikelihood( calculateLikelihood() );
        log.info( results.toString() );

        log.info( "Finished scenario in {}", sw );
        results.save();
    }

    /**
     * Finalise the application.
     */
    public final void finalise() {
        log.info( "Closing project" );
    }

    /**
     * Calculate the Monte Carlo score (the log-likelihood) for the simulation.
     *
     * @return the log-likelihood.
     */
    private double calculateLikelihood() {

        // Calculate the log-likelihood as
        // L = log(Factorial( N )) + sum(x_i*log(p_i)) - sum(log(x_i))
        // where p_i = the probability of observing i SNP differences (from observed differences)
        //       x_i = the number of times we observe i SNP differences in our simulation
        //       N = the total number of observed SNP differences
        // p_i = observedPairwiseDistancesDistribution.getFrequency( i )/n;
        // x_i = simulatededPairwiseDistancesDistribution.getFrequency( i );

        /*
        final IntegerDistribution observedPairwiseDistancesDistribution = settings.getObservedSnpDistribution();
        final IntegerDistribution simulatedPairwiseDistancesDistribution = settings.getResults().getSnpDistanceDistribution();

        if (simulatedPairwiseDistancesDistribution.getSumCounts() == 0) {
            log.warn( "Could not calculate likelihood : no infection detected");
            return Double.NEGATIVE_INFINITY;
        }

        final Integer n = observedPairwiseDistancesDistribution.getSumCounts();

        double sumXFactorial = 0.0;
        double sumXlogP = 0.0;
        for (int i : simulatedPairwiseDistancesDistribution.getBins()) {
            int x_i = simulatedPairwiseDistancesDistribution.getFrequency( i );
            Integer c_i = observedPairwiseDistancesDistribution.getFrequency( i );
            if (c_i != null) {
                double p_i = ((double) c_i)/ n;
                sumXlogP += x_i * Math.log( p_i );
            }
            sumXFactorial += Factorial.lnFactorial( x_i );
        }

        // We only define the log-likelihood up to a multiplicative factor
        //final double nFactorial = Factorial.lnFactorial( n );
        //final double logLikelihood = nFactorial + sumXlogP - sumXFactorial ;
        final double logLikelihood = sumXlogP - sumXFactorial ;

        log.debug( "logLikelihood : {}", logLikelihood );
        return logLikelihood;

         */

        final double[] probabilities = new double[settings.getObservedSnpDistribution().getNumBins()];
        final int[] x = new int[settings.getObservedSnpDistribution().getNumBins()];
        final int total = settings.getObservedSnpDistribution().getSumCounts();
        {
            int i = 0;
            for (Integer bin : settings.getObservedSnpDistribution().getBins()) {
                x[i] = settings.getObservedSnpDistribution().getFrequency( bin );
                probabilities[i] = (1.0 * x[i]) / total;
                i++;
            }
        }

        MultinomialDistribution dist = new MultinomialDistribution(total, probabilities);
        if (dist != null) {
            final int expectedCount = settings.getObservedSnpDistribution().getSumCounts();

            // If I want to match the observed distribution exactly then the bins in the pairwise distribution
            // should be the same as the observed distribution then I need to include the following statement
            // else - I don't care - just ignore the SNP differences that are too large.
            if (settings.getResults().getSnpDistanceDistribution().getNumBins() > settings.getObservedSnpDistribution().getNumBins()) {
                log.warn("Could not calculate likelihood - the max SNP differences of the observed dists do not match the calculated {}.", settings.getResults().getSnpDistanceDistribution().toCsv());
                return Double.NEGATIVE_INFINITY;
            }

            final IntegerDistribution generatedPairwiseDistancesDistribution = new IntegerDistribution(settings.getObservedSnpDistribution().getNumBins());
            for (int bin : generatedPairwiseDistancesDistribution.getBins()) {
                Integer data = settings.getResults().getSnpDistanceDistribution().getFrequency(bin);
                if (data == null) {
                    data = 0;
                }
                generatedPairwiseDistancesDistribution.setFrequency(bin, data);
            }

            if (generatedPairwiseDistancesDistribution.getSumCounts() == 0) {
                log.warn("Could not calculate likelihood : {}", generatedPairwiseDistancesDistribution.toCsv());
                return Double.NEGATIVE_INFINITY;
            }

            final int[] bins = generatedPairwiseDistancesDistribution.normaliseBins(expectedCount).toArray();

            // We want the log-likelihood here so we copy most of the code from MultinomialDistribution but
            // neglecting the final Math.exp() call.
            double sumXFact = 0.0;
            int sumX = 0;
            double sumPX = 0.0;
            final double[] p = dist.getP();

            for (int i = 0; i < p.length; i++) {
                sumX += bins[i];
                sumXFact += broadwick.math.Factorial.lnFactorial(bins[i]);
                if (p[i] > 1E-15) {
                    // just in case probabilities[i] == 0.0
                    sumPX += (bins[i] * Math.log(p[i]));
                }
            }

            if (sumX != dist.getN()) {
                log.error("Cannot calculate the Monte Carlo score for snp distance distribution {}; compared to {}",
                        generatedPairwiseDistancesDistribution.toCsv(), settings.getObservedSnpDistribution().toCsv());
                throw new IllegalArgumentException(String.format("Multinomial distribution error: Sum_x [%d] != number of samples [%d].", sumX, dist.getN()));
            } else {
                final double logLikelihood = broadwick.math.Factorial.lnFactorial(dist.getN()) - sumXFact + sumPX;
                log.debug("logLikelihood : {}", logLikelihood);
                return logLikelihood;
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Update the transition kernel.
     *
     * @return the updated Kernel.
     */
    protected final TransitionKernel updateKernel() {
        TransitionKernel kernel = simulator.getTransitionKernel();
        kernel.clear();

        final int cur_date = ((int) simulator.getCurrentTime());

        //loop over all farms, adding their kernels.
        for (Farm farm : farms.values()) {
            if (!farm.getInfectedCows().isEmpty()) {
                for (Map.Entry<SimulationEvent, Double> event : farm.createTransitionKernel(cur_date).entrySet()) {
                    kernel.addToKernel( event.getKey(), event.getValue() );
                }
            }
        }

        if (settings.isBadgersModelledExplicitly()) {
            for (Sett sett : setts.values()) {
                for (InfectedBadger badger : sett.getInfectedBadgers()) {
                    // add the event of the infected badger dying.
                   int days_infected = cur_date - badger.getDateInfected();
                    ExponentialDistribution dist = new ExponentialDistribution(1.0/settings.getInfectedBadgerLifetime());
                    kernel.addToKernel(new ScenarioTransmissionEvent(badger, badger,
                                    settings.getGenerator().selectOneOf(sett.getConnectedFarms())),
                            dist.cumulativeProbability( days_infected ));
                }
            }
        }
        return kernel;
    }

    /**
     * Create some infected animals and add them to farms. The farms and the probabilities for being in each infection state
     * is read from the configuration file.
     */
    private void seedInfectedAnimals() {
        int infectionsAdded = 0;
        while (infectionsAdded == 0) {
            // this just makes sure that we're not starting a simulation with NO infections.
            log.info( "Seeding infections" );
            for (String infection : settings.getInitialInfectionStates().split( ";" )) {
                final String[] split = infection.split( ":" );
                final Double[] probsAsCsv = broadwick.utils.ArrayUtils.toDoubleArray( split[2] );
                final Farm farm = farms.get( split[1].trim() );

                InfectionState initialInfectionState = settings.getGenerator().selectOneOf( InfectionState.values(),
                        ArrayUtils.toPrimitive( probsAsCsv ) );

                if (initialInfectionState != InfectionState.SUSCEPTIBLE) {
                    Set<Integer> snps = settings.generateSnps( -1, settings.getStartDate() );

                    InfectedCow cow = new InfectedCow( split[0], snps, settings.getStartDate(), initialInfectionState );
                    farm.getInfectedCows().add( cow );
                    results.getInfectedCows().add( cow );
                    results.recordReactors( 1 );
                    log.debug( "{}", String.format( "Seeding infected cow %s (%s) on farm %s", split[0], initialInfectionState, split[1] ) );
                    infectionsAdded++;

                    final InfectionTree infectionTree = settings.getResults().getInfectionTree();
                    infectionTree.insert( infectionTree.getRoot(), cow );

                    if (settings.isReservoirsIncluded()) {
                        Sett sett = settings.getGenerator().selectOneOf( farm.getSetts() );

                        // Add an infected badger to this reservoir, assume they were infected at some
                        // [uniformly distributed] time up to settings.getInfectedBagetLifetime() in the
                        // past.
                        int dateInfected = settings.getStartDate() - settings.getGenerator().getInteger(0, ((int)settings.getInfectedBadgerLifetime()));
                        InfectedBadger badger = new InfectedBadger( String.format( "Badger_seed_%05d", settings.getNextBadgerId() ),
                                snps, settings.getStartDate(), dateInfected );
                        sett.getInfectedBadgers().add( badger );
                        infectionsAdded++;
                        infectionTree.insert( infectionTree.getRoot(), badger );
                    }
                } else {
                    log.debug( "Not adding cow {} ({})", split[0], initialInfectionState );
                }
            }
        }
    }

    /**
     * Mark some herds as being under movement restriction. The number of herds is set in the configuration file but
     * the actual herds are chosen at random.
     */
    private void markRestrictedHerds() {
        Collection<Farm> restrictedFarms = settings.getGenerator().selectManyOf( farms.values(),
                settings.getNumInitialRestrictedHerds() );
        if (log.isTraceEnabled()) {
            log.trace( "Initialising the scenario with the following herds under restrictions {}",
                    restrictedFarms.stream().map( Farm::getId ).collect( Collectors.joining( "," ) ) );
        }
        final int testIntervalInDays = settings.getTestIntervalInYears() * 365;
        for (Farm farm : farms.values()) {
            if (restrictedFarms.contains( farm )) {
                farm.setRestricted( true );
                final int previousTest = settings.getStartDate() - settings.getGenerator().getInteger( 0, 60 );
                if (settings.getGenerator().getDouble() < 0.5) {
                    farm.setLastClearTestDate( -1 );
                    farm.setLastPositiveTestDate( previousTest );
                    farm.setNextWHTDate( previousTest + 60 );
                    farm.setNumClearTests( 0 );
                } else {
                    farm.setLastPositiveTestDate( previousTest - 60 );
                    farm.addClearTest( previousTest );
                    farm.setNumClearTests( 1 );
                }
            } else {
                final int previousTest = settings.getStartDate() - settings.getGenerator().getInteger( 0, testIntervalInDays );
                farm.setLastPositiveTestDate( -1 );
                farm.setLastClearTestDate( previousTest );
                farm.setNumClearTests( -1 );
                farm.setNextWHTDate( previousTest + testIntervalInDays );
                farm.setRestricted( false );
            }
        }
    }

    /**
     * Create the farms used in the simulation.
     */
    private void createFarms() {
        log.info( "Reading farm definition from <{}> ", settings.getFarmIdFile() );
        farms = new HashMap<>();
        try {
            java.util.Scanner sc = new java.util.Scanner( new File( settings.getFarmIdFile() ), Charset.defaultCharset().name() );
            while (sc.hasNextLine()) {
                final String id = sc.nextLine();
                farms.put( id, new Farm( id, settings ) );
            }
        } catch (java.io.IOException ex) {
            log.error( "Caught Exception reading config file {} : {}", configFile, ex.getLocalizedMessage() );
            throw new BroadwickException( "Failed to read farm ids" );
        }
    }

    /**
     * Create the badger setts. Each farm within 2km are linked by a reservoir, once the reservoir data
     * is read, any farm that does not have a reservoir connected will be given one.
     */
    private void createSetts() {
        log.info( "Reading reservoir definition from <{}> ", settings.getSettIdFile() );
        setts = new HashMap<>();
        try {
            Collection<String> farmsWithSetts = new HashSet<>();
            final FileInputIterator fle = new FileInput( settings.getSettIdFile() ).iterator();
            while (fle.hasNext()) {
                final String[] tokens = fle.next().split( ":" );
                final String settId = tokens[0];
                // Take the list of farm ids and find the corresponding farms.
                Collection<String> farmIds = Arrays.stream( tokens[1].split( "," ) )
                        .map( String::trim )
                        .collect( Collectors.toList() );
                Collection<Farm> farmList = this.farms.entrySet().stream()
                        .filter( e -> farmIds.contains( e.getKey() ) )
                        .map( Map.Entry::getValue )
                        .collect( Collectors.toList() );

                final Sett sett = new Sett( settId, farmList );
                setts.put( settId, sett );
                for (String id : farmIds) {
                    this.farms.get( id ).getSetts().add( sett );
                }
                farmsWithSetts.addAll( farmIds );
            }

            // Now add connectedReservoirs to those farms without one.
            int settId = 0;
            Collection<Farm> farmsWithoutSetts = farms.entrySet().stream()
                    .filter( e -> !farmsWithSetts.contains( e.getKey() ) )
                    .map( Map.Entry::getValue )
                    .collect( Collectors.toList() );
            for (Farm farm : farmsWithoutSetts) {
                final String id = String.format( "RESERVOIR_X%07d", ++settId );
                final Sett sett = new Sett( id, Arrays.asList( farm ) );
                setts.put( id, sett );
                farms.get( farm.getId() ).getSetts().add( sett );
            }

            // all farms should now have a connected reservoir
        } catch (java.io.IOException e) {
            log.error( "Could not read reservoir definitions from {}", settings.getSettIdFile() );
            throw new BroadwickException( "Failed to read reservoir definitions" );
        }
    }

    private void readSlaughterhouseMoves() {
        log.info( "Reading slaughterhouse moves from <{}> ", settings.getSlaughterhouseMovesFile() );
        try {
            final FileInputIterator fle = new FileInput( settings.getSlaughterhouseMovesFile() ).iterator();
            while (fle.hasNext()) {
                final String[] tokens = fle.next().split( ":" );
                final int date = Integer.parseInt( tokens[0].trim() );
                for (String farmId : tokens[1].split( "," )) {
                    final Farm farm = farms.get( farmId.trim() );
                    if (farm != null) {
                        farm.getDatesOfSlaughterhouseMoves().add( date );
                    }
                }
            }
        } catch (Exception e) {
            log.error( "Could not read slaughterhouse movement distribution from {}", settings.getSlaughterhouseMovesFile() );
            throw new BroadwickException( "Failed to read slaughterhouse movement distribution" );
        }
    }

    /**
     * Read the distribution of observed SNP distributions.
     */
    private void readObservedSnpDistribution() {
        log.info( "Reading observed pairwise snp distance distribution from <{}>", settings.getObservedSnpPairwiseDistanceFile() );

        try (FileInput fileInput = new FileInput( settings.getObservedSnpPairwiseDistanceFile() )) {
            final FileInputIterator iterator = fileInput.iterator();
            while (iterator.hasNext()) {
                final String line = iterator.next();
                if (line != null) {
                    final String[] split = line.split( ":" );
                    final int x = Integer.parseInt( split[0].trim() );
                    final int frequency = Integer.parseInt( split[1].trim() );
                    settings.getObservedSnpDistribution().setFrequency( x, frequency );
                }
            }
        } catch (IOException e) {
            log.error( "Could not read distribution from {}", settings.getObservedSnpPairwiseDistanceFile() );
            throw new BroadwickException( "Failed to read observed SNP distribution distribution" );
        }
    }

    /**
     * Read the rate at which animals are sampled.
     */
    private void readSamplingRatesPerYear() {
        log.debug( "Reading observed sampling rate distribution from <{}>", settings.getSamplingRateFile() );

        try (FileInput rates = new FileInput( settings.getSamplingRateFile() )) {
            final FileInputIterator iterator = rates.iterator();
            while (iterator.hasNext()) {
                final String line = iterator.next();
                if (!line.startsWith( "#" ) && !line.isEmpty()) {
                    final String[] lineTokens = line.split( "," );
                    final int year = Integer.parseInt( lineTokens[0] );
                    final double percentageSamplesGrown = Double.parseDouble( lineTokens[3] );

                    settings.getSamplingRatesPerYear().put( year, percentageSamplesGrown );
                }
            }
        } catch (IOException ex) {
            log.error( "Could not read sampling rates {}", ex.getLocalizedMessage() );
            throw new BroadwickException( "Failed to read sampling rate distribution" );
        }
        log.debug( "Distribution of grown samples {}", settings.getSamplingRatesPerYear().toString() );
    }

    /**
     * Read the movement frequencies.
     */
    private int readMovementFrequencies() {
        int numMoves = 0; // the total number of animals moved
        try {
            log.info( "Reading movement frequencies from <{}>", settings.getMovementFrequenciesFile() );
            final StopWatch sw = new StopWatch();
            sw.start();
            int numMovementEvents = 0; // the number of movements (each movement can contain several animals).
            int numMovesToSelf = 0;
            final FileInputIterator fle = new FileInput( settings.getMovementFrequenciesFile() ).iterator();
            while (fle.hasNext()) {
                final String[] split = fle.next().split( " " );
                final String[] farmIds = split[0].split( "-" );
                final Integer[] numAnimalsMoved = broadwick.utils.ArrayUtils.toIntegerArray( split[1] );
                if (farms.keySet().contains( farmIds[0] ) && farms.keySet().contains( farmIds[1] )) {
                    if (!farmIds[0].equals( farmIds[1] )) {
                        // ignore self moves - if they are legitimate moves then they would probably be
                        // covered by CTS links so would not be tested!
                        settings.getMovementFrequencies().add( new Pair<>( farmIds[0], farmIds[1] ) );
                        for (int num : numAnimalsMoved) {
                            farms.get( farmIds[0] ).getOffMovementDistribution().setFrequency( num );
                            numMoves += num;
                        }
                        ++numMovementEvents;
                    } else {
                        ++numMovesToSelf;
                    }
                } else {
                    log.error( "Ignoring unknown farm in movement {}->{}", farmIds[0], farmIds[1] );
                }
            }
            log.debug( "{}", String.format( "Read %d movements in %s (ignoring %d moves to self)", numMovementEvents, sw, numMovesToSelf ) );

        } catch (IOException e) {
            log.error( "Could setup movement distributions. {}", Throwables.getStackTraceAsString( e ) );
        }
        return numMoves;
    }

    private Collection<Farm> getInfectedFarms() {
        return farms.values().stream()
                .filter( farm -> (farm.getInfectedCows().size() > 0) )
                .collect( Collectors.toList() );
    }

    private Collection<Farm> getRestrictedFarms() {
        return farms.values().stream()
                .filter( farm -> (farm.isRestricted()) )
                .collect( Collectors.toList() );
    }

    /**
     * Invocation point.
     * <p>
     *
     * @param args the command line arguments passed to Broadwick.
     */
    public static void main(final String[] args) {
        final CommandLineOptions cli = new CommandLineOptions( args );

        final NIBtbClusterScenario app = new NIBtbClusterScenario( cli );
        app.configFile = cli.getConfigFileName();
        app.parametersFile = cli.getParameterFileName();

        try {
            app.init( cli.getScenarioId() );
            app.run();
            app.finalise();
        } catch (BroadwickException ex) {
            app.getLog().error( "Something went wrong starting project. See the error message." );
            app.getLog().trace( Throwables.getStackTraceAsString( ex ) );
        }
    }

    @Getter
    private Logger log;
    @Getter
    private StochasticSimulator simulator;
    @Getter
    private ScenarioResults results;
    private String configFile;
    private String parametersFile;
    private ProjectSettings settings;
    @Getter
    private Map<String, Farm> farms;
    private Map<String, Sett> setts;
    private static final long serialVersionUID = 201903290000000001L;
}
