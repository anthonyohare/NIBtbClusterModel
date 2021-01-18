package btbcluster;

import broadwick.BroadwickException;
import broadwick.LoggingFacade;
import broadwick.math.Matrix;
import broadwick.math.Vector;
import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import lombok.Getter;
import org.slf4j.Logger;
import org.apache.commons.io.FilenameUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.*;

public class NIBtbClusterController {

    /**
     * Create the application, setting up the logging.
     *
     * @param cli the command line arguments.
     */
    public NIBtbClusterController(final CommandLineOptions cli) {

        //Set up the logger
        final String logConsoleFormatMsg = "[%thread] %-5level %msg %n";
        final String logFileFormatMsg = "%d{HH:mm:ss.SSS} %-5level %class.%line %msg %n";
        final LoggingFacade logFacade = new LoggingFacade();
        log = logFacade.getRootLogger();
        try {
            // The valid levels are INFO, DEBUG, TRACE, ERROR, ALL
            final String logFile = "NIBtbClusterController.log";
            logFacade.addFileLogger( logFile, cli.getLoggingLevel(), logFileFormatMsg, true );
            logFacade.addConsoleLogger( "INFO", logConsoleFormatMsg );

            logFacade.getRootLogger().setLevel( Level.TRACE );
        } catch (BroadwickException ex) {
            log.error( "{}\nSomething went wrong starting project. See the error messages.",
                    ex.getLocalizedMessage() );
        }
    }

    private void init() {
        log.info( "Initialising project from file [configurations: {}]", configFile );
        settings = new ProjectSettings( readFile( configFile ) );
    }

    private void run() {

        ProjectParameters parameters = new ProjectParameters( settings, readFile( settings.getParametersFile() ) );
        ProjectState state = new ProjectState(settings.getStateFile());
        final ControllerResults results = readResults();

        if (state.getNumSteps() == 0) {
            // this is the first step, generate the parameters
            parameters.generateParametersFile(true);
            state.setCurrentStep("");
            state.setLastStepAccepted( true ); // always accept the first step
            double[] step = {parameters.getBeta(), parameters.getSigma(), parameters.getGamma(),
                    parameters.getAlpha(), parameters.getAlphaPrime(),
                    parameters.getTestSensitivity(), parameters.getMutationRate()};
            // if contains badger lifetime - add it here.....
            if (settings.getBadgerLifetimeModelled()) {
                step = new double[]{parameters.getBeta(), parameters.getSigma(), parameters.getGamma(),
                        parameters.getAlpha(), parameters.getAlphaPrime(),
                        parameters.getTestSensitivity(), parameters.getMutationRate(),
                        parameters.getInfectedBadgerLifetime()};
                state.setMeans( new Vector(step.length) );
                state.setCovariances(new Matrix(step.length,step.length));
            }
            for (int i=0; i<step.length; i++) {
                state.getCovariances().setEntry(i,i, settings.getPercentageDeviation()*step[i]*0.01);
                state.getMeans().setEntry( i, step[i] );
            }
            log.debug("Saving parameters file");
            createResults();
        } else {
            // we have run the simulation at the proposed step, check if we accept it or not, save the
            // results and propose another step.

            if (acceptProposedStep(state, results)) {
                log.trace("Accepted step");
                state.setCurrentStep(state.getProposedStep());
                state.setNumAcceptedSteps(state.getNumAcceptedSteps() + 1);
                state.setLastStepAccepted( true );
                if (results.getLogLikelihood().getSize() == 0) {
                    state.setLogLikelihood( Double.NEGATIVE_INFINITY);
                } else {
                    state.setLogLikelihood( results.getLogLikelihood().getMean() );
                }
                saveScenarioData(results);
            } else {
                log.trace("Rejected step");
                state.setLastStepAccepted( false );
            }

            // save the current step and the results to a file.
            saveResults( results, parameters, state );

            // propose a new step
            log.debug("Generating new parameters");
            parameters.generateStep(state);
            parameters.generateParametersFile( false );
        }

        // now update the state file and save it.
        state.setNumSteps( state.getNumSteps() + 1 );
        state.setProposedStep(parameters.toString());
        state.setRngSeed( settings.getGenerator().getInteger(Integer.MIN_VALUE, Integer.MAX_VALUE) );
        state.save();

        log.trace("Saving state file and exiting");
    }

    private boolean acceptProposedStep(ProjectState state, final ControllerResults results ) {
        if (state.getNumSteps() == 1) {
            // this is the first step, accept it
            return true;
        }

        if (results.getLogLikelihood().getSize() > 0) {
            // we are using a log likelihood, so...
            if (state.getLogLikelihood() == Double.NEGATIVE_INFINITY) {
                // the last step didn't have a likelihood so we should accept this one.
                return true;
            }
            final double ratio = results.getLogLikelihood().getMean()- state.getLogLikelihood();
            final double rand = Math.log(settings.getGenerator().getDouble());
            log.trace("{}", String.format("Math.log(%g) < (%g-%g) [=%g]?",
                    rand, results.getLogLikelihood().getMean(), state.getLogLikelihood(),
                    ratio / settings.getSmoothingRatio()));

            return rand < ratio / settings.getSmoothingRatio();
        }
        log.trace("likelihood = -Infinity, rejecting step.");

        return false;
    }

    private ControllerResults readResults() {

        List<String> filenames = new ArrayList<>();
        for (int i = 0; i < settings.getNumScenarios(); i++) {
            filenames.add( String.format(settings.getResultsFile(), i ) );
        }
        log.debug( "Reading results from {} / {}", settings.getResultsDir(), filenames );

        ControllerResults results = new ControllerResults();
        ObjectMapper mapper = new ObjectMapper();
        for (String filename : filenames) {
            String resultsFileName = String.format( "%s%s%s", settings.getResultsDir(), File.separator, filename );
            try {
                final File resultsFile = new File( resultsFileName );
                if (resultsFile.exists()) {
                    final JsonNode jsonNode = mapper.readTree( resultsFile );

                    if (Double.NEGATIVE_INFINITY != jsonNode.get( "loglikelihood" ).asDouble()) {
                        results.getNumCowCowTransmissions().add( jsonNode.get( "numCowCowTransmissions" ).asDouble() );
                        results.getNumCowBadgerTransmissions().add( jsonNode.get( "numCowBadgerTransmissions" ).asDouble() );
                        results.getNumBadgerCowTransmissions().add( jsonNode.get( "numBadgerCowTransmissions" ).asDouble() );
                        results.getNumReactors().add( jsonNode.get( "numReactors" ).asDouble() );
                        results.getNumBreakdowns().add( jsonNode.get( "numBreakdowns" ).asDouble() );
                        results.getNumDetectedAnimalsAtSlaughter().add( jsonNode.get( "numDetectedAnimalsAtSlaughter" ).asDouble() );
                        results.getNumUndetectedAnimalsAtSlaughter().add( jsonNode.get( "numUndetectedAnimalsAtSlaughter" ).asDouble() );
                        results.getNumInfectedAnimalsMoved().add( jsonNode.get( "numInfectedAnimalsMoved" ).asDouble() );
                        results.getLogLikelihood().add( jsonNode.get( "loglikelihood" ).asDouble() );
                        results.addReactorsAtBreakdownDistribution( jsonNode.get( "reactorsAtBreakdownDistribution" ).asText() );
                        results.addSnpDistanceDistribution( jsonNode.get( "snpDistanceDistribution" ).asText() );
                    }
                }
            } catch (IOException e) {
                log.error( "Error {}", e.getLocalizedMessage() );
                throw new BroadwickException( e );
            }
        }
        log.debug( "Results = {}", results );

        /*
        // now that we have read the results, delete them so they don't get used mistakenly.
        for (String filename : filenames) {
            String resultsFileName = String.format( "%s%s%s", settings.getResultsDir(), File.separator, filename );
            try {
                final File resultsFile = new File( resultsFileName );
                final boolean deleted = resultsFile.delete();
            } catch (Exception e) {
                log.error( "Error {}", e.getLocalizedMessage() );
                throw new BroadwickException( e );
            }
        }
         */

        return results;
    }

    private void createResults() {
        log.trace("Saving results to {}", settings.getOutputFile());
        try (FileWriter writer = new FileWriter( settings.getOutputFile(), false );
             PrintWriter pw = new PrintWriter( writer )) {
            final StringBuilder sb = new StringBuilder( 10 );
            sb.append( "#Broadwick version \n" );
            sb.append( "#Steps taken [1]\n" );
            sb.append( "#Current step accepted ? [2]\n" );
            sb.append( "#beta [3]\n" );
            sb.append( "#sigma [4]\n" );
            sb.append( "#gamma [5]\n" );
            sb.append( "#alpha [6]\n" );
            sb.append( "#alphaPrime [7]\n" );
            sb.append( "#test sensitivity [8]\n" );
            sb.append( "#mu [9]\n" );
            int i = 0;
            if (settings.getBadgerLifetimeModelled()) {
                i = 1;
                sb.append( "#infected badger lifespan [10]\n" );
            }
            sb.append( String.format("#Likelihood  (mean number, stddev) [%d-%d]\n", 10+i, 11+i));
            sb.append( String.format("#Num cow-cow transmission (mean number, stddev) [1%d-%d]\n", 12+i, 13+i));
            sb.append( String.format("#Num cow-badger transmission (mean number, stddev)  [%d-%d]\n", 14+i, 15+i));
            sb.append( String.format("#Num badger-cow transmission (mean number, stddev)  [%d-%d]\n", 16+i, 17+i));
            sb.append( String.format("#Num Reactors (mean, stddev) [%d-%d]\n", 18+i, 19+i));
            sb.append( String.format("#Num Breakdowns (mean, stddev)  [%d-%d]\n", 20+i, 21+i));
            sb.append( String.format("#Num Infected Animals moved (mean, stddev)  [%d-%d]\n", 22+i, 23+i));
            sb.append( String.format("#Num animals detected at slaughter (mean, stddev)  [%d-%d]\n", 24+i, 25+i));
            sb.append( String.format("#Num infections undetected at slaughter (mean, stddev)  [%d-%d]\n", 26+i, 27+i));
            sb.append( "\n" );

            pw.printf( sb.toString() );

        } catch (IOException e) {
            throw new BroadwickException( "Could not save output. " + e.getLocalizedMessage() );
        }
    }

    private void saveResults(final ControllerResults results, final ProjectParameters parameters,
                             final ProjectState state) {
        log.trace("Saving results to {}", settings.getOutputFile());
        try (FileWriter writer = new FileWriter( settings.getOutputFile(), true );
             PrintWriter pw = new PrintWriter( writer )) {
            final DecimalFormat decimalFormat = new DecimalFormat( "0.######E0" );
            final StringBuilder sb = new StringBuilder( 10 );

            sb.append(state.getNumSteps()).append(",");
            sb.append(state.isLastStepAccepted() ? 1 : 0).append(",");

            sb.append( decimalFormat.format( parameters.getBeta()) ).append( "," );
            sb.append( decimalFormat.format( parameters.getSigma() ) ).append( "," );
            sb.append( decimalFormat.format( parameters.getGamma() ) ).append( "," );
            sb.append( decimalFormat.format( parameters.getAlpha() ) ).append( "," );
            sb.append( decimalFormat.format( parameters.getAlphaPrime() ) ).append( "," );
            sb.append( decimalFormat.format( parameters.getTestSensitivity() ) ).append( "," );
            sb.append( decimalFormat.format( parameters.getMutationRate() ) ).append( "," );
            if (settings.getBadgerLifetimeModelled()) {
                sb.append( decimalFormat.format( parameters.getInfectedBadgerLifetime() ) ).append( "," );
            }

            if (results.getLogLikelihood().getSize() == 0){
                sb.append( "-Infinity,-Infinity," );
            } else {
                sb.append( decimalFormat.format( results.getLogLikelihood().getMean() ) ).append( "," );
                sb.append( decimalFormat.format( results.getLogLikelihood().getStdDev() ) ).append( "," );
            }

            sb.append( decimalFormat.format( results.getNumCowCowTransmissions().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumCowCowTransmissions().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumCowBadgerTransmissions().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumCowBadgerTransmissions().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumBadgerCowTransmissions().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumBadgerCowTransmissions().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumReactors().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumReactors().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumBreakdowns().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumBreakdowns().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumInfectedAnimalsMoved().getMean() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumInfectedAnimalsMoved().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumDetectedAnimalsAtSlaughter().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumDetectedAnimalsAtSlaughter().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumUndetectedAnimalsAtSlaughter().getStdDev() ) ).append( "," );
            sb.append( decimalFormat.format( results.getNumUndetectedAnimalsAtSlaughter().getStdDev() ) ).append( "," );
            sb.append( "\n" );

            pw.printf( sb.toString() );

        } catch (IOException e) {
            throw new BroadwickException( "Could not save output. " + e.getLocalizedMessage() );
        }
        // TODO -  save the time series data
    }

    private void saveScenarioData(final ControllerResults results) {

        String baseFileName = FilenameUtils.removeExtension( settings.getOutputFile() ) + "_snpDiffDistribution.txt";;
        StringBuilder sb = new StringBuilder(  );
        sb.append( "#Number of SNP differences [1]\n" );
        sb.append( "#Mean Frequency [2]\n" );
        sb.append( "#Standard Deviation Frequency [3]\n" );
        for (Integer diffs : results.getSnpDistanceDistribution().keySet()) {
            sb.append( diffs ).append("\t");
            sb.append(results.getSnpDistanceDistribution().get( diffs ).getSummary()).append( "\n" );
        }
        saveStringtoFile(baseFileName, sb.toString());


        baseFileName = FilenameUtils.removeExtension( settings.getOutputFile() ) + "_numReactorsAtBreakdownDistribution.txt";;
        sb = new StringBuilder(  );
        sb.append( "#Number of reactors at breakdown [1]\n" );
        sb.append( "#Mean Frequency [2]\n" );
        sb.append( "#Standard Deviation Frequency [3]\n" );
        for (Integer diffs : results.getReactorsAtBreakdownDistribution().keySet()) {
            sb.append( diffs ).append("\t");
            sb.append(results.getReactorsAtBreakdownDistribution().get( diffs ).getSummary()).append( "\n" );
        }
        saveStringtoFile(baseFileName, sb.toString());

    }

    private void saveStringtoFile(final String filename, final String data) {
        log.trace("Saving results to {}", filename);
        try (FileWriter writer = new FileWriter( filename, false );
             PrintWriter pw = new PrintWriter( writer )) {
            pw.print(data);
        } catch (IOException e) {
            throw new BroadwickException( "Could not save output. " + e.getLocalizedMessage() );
        }
    }

    private Map<String, String> readFile(final String name) {
        final Map<String, String> contents = new HashMap<>();

        File file = new File( name );
        try (Scanner sc = new Scanner( file, Charset.defaultCharset().name() )) {
            while (sc.hasNextLine()) {
                final String line = sc.nextLine();
                if (!(line.trim().isEmpty() || line.startsWith( "#" ))) {
                    final String[] tokens = line.split( "=" );
                    contents.put( tokens[0].trim(), tokens[1].trim() );
                }
            }
        } catch (FileNotFoundException e) {
            log.warn( "Could not find config file <{}>. This may not be a problem; if we cannot find a parameter file we will create it later.", name );
        }
        return contents;
    }

    /**
     * Invocation point.
     * <p>
     *
     * @param args the command line arguments passed to Broadwick.
     */
    public static void main(final String[] args) {
        final CommandLineOptions cli = new CommandLineOptions( args );

        final NIBtbClusterController app = new NIBtbClusterController( cli );
        app.configFile = cli.getConfigFileName();

        try {
            app.init();
            app.run();
        } catch (BroadwickException ex) {
            app.getLog().error( "Something went wrong starting project. See the error message." );
            app.getLog().trace( Throwables.getStackTraceAsString( ex ) );
        }
    }

    @Getter
    private Logger log;
    private String configFile;
    private ProjectSettings settings;
    private static final long serialVersionUID = 201903290000000001L;
}
