package btbcluster;

import broadwick.BroadwickConstants;
import broadwick.BroadwickException;
import broadwick.utils.Pair;
import broadwick.rng.RNG;
import broadwick.statistics.distributions.IntegerDistribution;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Store the settings for the project.
 */
@Slf4j
public class ProjectSettings implements Serializable {

    /**
     * Create the project settings by reading them from the given file.
     * @param id the id of the scenario.
     * @param results the results of the scenario.
     * @param configFile the name of the config file.
     * @param paramFile the name of the file containing the parameters for the scenario.
     */
    public ProjectSettings(final String id, final ScenarioResults results, final String configFile, final String paramFile) {
        this.id = id;
        this.results = results;
        final Map<String, String> configuration = new HashMap<>();

        try {
            final List<File> files = Arrays.asList(new File(configFile), new File(paramFile));

            for (File input : files) {
                Scanner sc = new Scanner(input, Charset.defaultCharset().name() );
                while (sc.hasNextLine()) {
                    final String line = sc.nextLine();
                    if (!(line.trim().isEmpty() || line.startsWith("#")) ){
                        final String[] tokens = line.split("=");
                        configuration.put(tokens[0].trim(), tokens[1].trim());
                    }
                }
            }
        } catch (IOException ex) {
            log.error("Caught Exception reading config file {} : {}", configFile, ex.getLocalizedMessage());
        }

        log.trace("Read Config: {}", configuration);

        // Configuration Items
        final String dateFormat = configuration.get("dateFormat");
        farmIdFile = configuration.get("farmIds");
        settIdFile = configuration.get("settIds");
        initialInfectionStates = configuration.get("initialInfectionStates");
        diversityModel = configuration.get("diversityModel");
        slaughterhouseMovesFile = configuration.get("slaughterhouseMovesFile");
        observedSnpPairwiseDistanceFile = configuration.get("observedSnpPairwiseDistanceFile");
        movementFrequenciesFile = configuration.get("movementFrequenciesFile");
        samplingRateFile = configuration.get("samplingRateFile");
        testIntervalInYears = Integer.parseInt(configuration.get("testIntervalInYears"));
        numInitialRestrictedHerds = Integer.parseInt(configuration.get("numInitialRestrictedHerds"));
        maxOutbreakSize = Integer.parseInt(configuration.get("maxOutbreakSize"));
        stepSize = Integer.parseInt(configuration.get("stepSize"));
        numMovements = Integer.parseInt(configuration.get("numMovements"));
        numSlaughters = Integer.parseInt(configuration.get("numSlaughters"));
        startDate = BroadwickConstants.getDate(configuration.get("startDate"), dateFormat);
        endDate = BroadwickConstants.getDate(configuration.get("endDate"), dateFormat);
        reservoirsIncluded = Boolean.parseBoolean(configuration.get("reservoirsIncluded"));

        // Parameters
        beta = Double.parseDouble(configuration.get("beta"));
        sigma = Double.parseDouble(configuration.get("sigma"));
        gamma = Double.parseDouble(configuration.get("gamma"));
        alpha = Double.parseDouble(configuration.get("alpha"));
        alphaPrime = Double.parseDouble(configuration.get("alphaPrime"));
        testSensitivity = Double.parseDouble(configuration.get("testSensitivity"));
        mutationRate = Double.parseDouble(configuration.get("mutationRate"));
        if (configuration.get("badgerLifetime") != null) {
            infectedBadgerLifetime = Double.parseDouble(configuration.get("infectedBadgerLifetime"));
            badgersModelledExplicitly = true;
        } else {
            badgersModelledExplicitly = false;
        }

        if (!ArrayUtils.contains(new String[]{"MAXIMUM", "MINIMUM", "INTERMEDIATE"}, diversityModel)) {
            throw new BroadwickException("Unrecognised diversity model: " + diversityModel);
        }
    }

    /**
     * Determine the SNP that is to be applied to a [mutated] strain. The algorithm is very simplistic as we do not need
     * to record where the SNP occurred in the genome so we have a static value that is incremented when we introduce a
     * new SNP.
     * @param day               the number of days over which SNPs are accumulated, if negative then there will be at
     *                          least one SNP generated.
     * @param lastSnpGeneration the day the last snp was generated.
     * @return a collection (HashSet) of snps that appeared since lastSnpGeneration
     */
    public Set<Integer> generateSnps(final long day, Integer lastSnpGeneration) {

        Set<Integer> snps = new HashSet<>();
        final long days = day - lastSnpGeneration;
        final long numSNPs;
        if (days < 0) {
            // numSNPs must be at least 1 - only used in initialising SNPs
            numSNPs = Math.max(1, generator.getPoisson(1));
        } else if (days == 0) {
            numSNPs = 0;
        } else {
            numSNPs = generator.getPoisson(mutationRate * days);
        }

        for (int i = 0; i < numSNPs; i++) {
            snps.add(++lastSnp);
        }
        return snps;
    }

    public int getNextCowId() {
        return ++nextCowId;
    }

    public int getNextBadgerId() {
        return ++nextBadgerId;
    }

    @Getter
    private String farmIdFile;
    @Getter
    private String settIdFile;
    @Getter
    private String initialInfectionStates;
    @Getter
    private String diversityModel;
    @Getter
    private String slaughterhouseMovesFile;
    @Getter
    private String movementFrequenciesFile;
    @Getter
    private String samplingRateFile;
    @Getter
    private String observedSnpPairwiseDistanceFile;
    @Getter
    private int numMovements;
    @Getter
    private int numSlaughters;
    @Getter
    private boolean reservoirsIncluded;
    @Getter
    private int testIntervalInYears;
    @Getter
    private int numInitialRestrictedHerds;
    @Getter
    private int startDate;
    @Getter
    private int endDate;
    @Getter
    private int stepSize;
    @Getter
    private int maxOutbreakSize;
    @Getter
    private double beta;
    @Getter
    private double sigma;
    @Getter
    private double gamma;
    @Getter
    private double alpha;
    @Getter
    private double alphaPrime;
    @Getter
    private double testSensitivity;
    @Getter
    private double mutationRate;
    @Getter
    private double infectedBadgerLifetime;
    @Getter
    private boolean badgersModelledExplicitly;
    @Getter
    private final ScenarioResults results;
    @Getter
    private final RNG generator = new RNG(RNG.Generator.Well19937c);
    @Getter
    private IntegerDistribution observedSnpDistribution = new IntegerDistribution();
    @Getter
    private final Collection<InfectionState> detectableStates = Arrays.asList(InfectionState.TESTSENSITIVE,
            InfectionState.INFECTIOUS);
    @Getter
    private Map<Integer, Double> samplingRatesPerYear = new HashMap<>();
    @Getter
    private final List<Pair<String, String>> movementFrequencies = new ArrayList<>();
    private String id = "0";
    private int lastSnp = 0;
    private int nextBadgerId = 0;
    private int nextCowId = 0;
    private static final long serialVersionUID = 201903290000000001L;
}
