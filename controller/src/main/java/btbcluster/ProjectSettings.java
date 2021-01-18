package btbcluster;

import broadwick.rng.RNG;
import lombok.Getter;
import broadwick.statistics.distributions.Uniform;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;

@Slf4j
public class ProjectSettings {

    public ProjectSettings (final Map<String, String> configuration ) {
        log.trace("Read Config: {}", configuration);

        numScenarios = Integer.parseInt(configuration.get("numScenarios"));
        smoothingRatio = Integer.parseInt(configuration.get("smoothingRatio"));
        percentageDeviation = Double.parseDouble(configuration.get("percentageDeviation"));
        parametersFile = configuration.get("parametersFile");
        resultsDir = configuration.get("resultsDir");
        resultsFile = configuration.get("resultsFile");
        outputFile = configuration.get("outputFile");
        stateFile = configuration.get("stateFile");

        if (configuration.containsKey("includeBadgers")) {
            badgerLifetimeModelled = Boolean.parseBoolean( configuration.get( "includeBadgers" ) );
        } else {
            badgerLifetimeModelled = Boolean.FALSE;
        }

        betaDist = getDist(configuration.get("betaRange"));
        sigmaDist = getDist(configuration.get("sigmaRange"));
        gammaDist = getDist(configuration.get("gammaRange"));
        alphaDist = getDist(configuration.get("alphaRange"));
        alphaPrimeDist = getDist(configuration.get("alphaPrimeRange"));
        testSensitivityDist = getDist(configuration.get("testSensitivityRange"));
        mutationRateDist = getDist(configuration.get("mutationRateRange"));
        if (badgerLifetimeModelled) {
            infectedBadgerLifetimeDist = getDist( configuration.get( "infectedBadgerLifetime" ) );
        } else {
            infectedBadgerLifetimeDist = new UniformPriorDistribution(0.0, 1.0);
        }
    }

    public void setRNGSeed(final int seed) {
        generator.seed( seed );
    }

    /**
     * From the tuple of number given in the config file, create a distribution object.
     * @param conf the tuple string from the config file.
     * @return the uniform distribution object.
     */
    private UniformPriorDistribution getDist(final String conf) {
        final String[] split = conf.split( ":" );
        return new UniformPriorDistribution(Double.parseDouble(split[0].trim()), Double.parseDouble(split[1].trim()));
    }


    @Getter
    private final int numScenarios;
    @Getter
    private final int smoothingRatio;
    @Getter
    private final double percentageDeviation;
    @Getter
    private final String parametersFile;
    @Getter
    private final String resultsDir;
    @Getter
    private final String resultsFile;
    @Getter
    private final String outputFile;
    @Getter
    private final String stateFile;
    @Getter
    private final Boolean badgerLifetimeModelled;
    @Getter
    private final RNG generator = new RNG(RNG.Generator.Well19937c);
    @Getter
    private final UniformPriorDistribution betaDist;
    @Getter
    private final UniformPriorDistribution sigmaDist;
    @Getter
    private final UniformPriorDistribution gammaDist;
    @Getter
    private final UniformPriorDistribution alphaDist;
    @Getter
    private final UniformPriorDistribution alphaPrimeDist;
    @Getter
    private final UniformPriorDistribution testSensitivityDist;
    @Getter
    private final UniformPriorDistribution mutationRateDist;
    @Getter
    private final UniformPriorDistribution infectedBadgerLifetimeDist;
}

/**
 * A uniform distribution class for a prior.
 */
class UniformPriorDistribution {

    public UniformPriorDistribution(final double low, final double high) {
        this.lower = low;
        this.upper = high;
        this.distribution = new Uniform( low, high );
    }

    /**
     * Get a sample from the distribution.
     * @return a random sample from the distribution.
     */
    public double sample() {
        return distribution.sample();
    }

    private Uniform distribution;
    @Getter
    private final double lower;
    @Getter
    private final double upper;
}
