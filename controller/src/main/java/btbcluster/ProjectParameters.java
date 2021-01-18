package btbcluster;

import broadwick.BroadwickException;
import broadwick.math.Vector;
import broadwick.statistics.distributions.TruncatedMultivariateNormalDistribution;
import lombok.Getter;
import org.apache.commons.lang.ArrayUtils;

import java.io.*;
import java.util.Map;

public class ProjectParameters {

    public ProjectParameters(final ProjectSettings settings, final Map<String, String> parameters) {
        this.settings = settings;
        sigma = Double.NaN;
        beta = Double.NaN;
        gamma = Double.NaN;
        alpha = Double.NaN;
        alphaPrime = Double.NaN;
        testSensitivity = Double.NaN;
        mutationRate = Double.NaN;
        infectedBadgerLifetime = Double.NaN;

        // Initialise the mean and covariance matrix that we will update during the simulation.
        // We want the vector elements to be consistent so we iterate over the priors.
        if (settings.getBadgerLifetimeModelled()) {
            double[] lowers = {settings.getBetaDist().getLower(), settings.getSigmaDist().getLower(),
                    settings.getGammaDist().getLower(), settings.getAlphaDist().getLower(),
                    settings.getAlphaPrimeDist().getLower(), settings.getTestSensitivityDist().getLower(),
                    settings.getMutationRateDist().getLower(), settings.getInfectedBadgerLifetimeDist().getLower()};
            double[] uppers = {settings.getBetaDist().getUpper(), settings.getSigmaDist().getUpper(),
                    settings.getGammaDist().getUpper(), settings.getAlphaDist().getUpper(),
                    settings.getAlphaPrimeDist().getUpper(), settings.getTestSensitivityDist().getUpper(),
                    settings.getMutationRateDist().getUpper(), settings.getInfectedBadgerLifetimeDist().getUpper()};
            this.lower = new Vector( lowers );
            this.upper = new Vector( uppers );
        } else {
            double[] lowers = {settings.getBetaDist().getLower(), settings.getSigmaDist().getLower(),
                    settings.getGammaDist().getLower(), settings.getAlphaDist().getLower(),
                    settings.getAlphaPrimeDist().getLower(), settings.getTestSensitivityDist().getLower(),
                    settings.getMutationRateDist().getLower()};
            double[] uppers = {settings.getBetaDist().getUpper(), settings.getSigmaDist().getUpper(),
                    settings.getGammaDist().getUpper(), settings.getAlphaDist().getUpper(),
                    settings.getAlphaPrimeDist().getUpper(), settings.getTestSensitivityDist().getUpper(),
                    settings.getMutationRateDist().getUpper()};
            this.lower = new Vector( lowers );
            this.upper = new Vector( uppers );
            }

        if (!parameters.isEmpty()) {
            // If we are starting the controller we will not have parameters to read!
            readParameters( parameters );
        }
    }

    public void readParameters(final Map<String, String> parameters) {
        beta = Double.parseDouble(parameters.get("beta"));
        sigma = Double.parseDouble(parameters.get("sigma"));
        gamma = Double.parseDouble(parameters.get("gamma"));
        alpha = Double.parseDouble(parameters.get("alpha"));
        alphaPrime = Double.parseDouble(parameters.get("alphaPrime"));
        testSensitivity = Double.parseDouble(parameters.get("testSensitivity"));
        mutationRate = Double.parseDouble(parameters.get("mutationRate"));
        if (settings.getBadgerLifetimeModelled()) {
            infectedBadgerLifetime = Double.parseDouble( parameters.get( "infectedBadgerLifetime" ) );
        }
    }

    public void generateStep(final ProjectState state) {

        double[] step = {beta, sigma, gamma, alpha, alphaPrime, testSensitivity, mutationRate};
        if (settings.getBadgerLifetimeModelled()) {
            step = AddToArray(step, infectedBadgerLifetime);
        }
        final double[] means = state.getMeans().toArray();

        System.out.println("means = " + new Vector(means).toString());
        System.out.println("step = " + new Vector(step).toString());
        // We sample from a multivariate normal distribution here,
        // the means for this distribution are the current values of the step: step.getValues()
        // The covariances are the updated at each step according to the adaptive Metropolis algorithm from
        // http://dept.stat.lsa.umich.edu/~yvesa/afmp.pdf, continuously updating the covraiances in this way
        // means we don't have to store the whole chain.
        final int n = step.length;
        final double scale = 2.85 / Math.sqrt(n);
        final int stepsTaken = state.getNumSteps() + 1;

        // Now update the means and covariances.
        // Doing it after the proposed step means that the means are ALWAYS updated (even for rejected steps) doing
        // it here means that only the accepted steps (i.e. the members of the chain) are used but the means are
        // skewed by using the same values repeatedly
        for (int i = 0; i < n; i++) {
            final double value = means[i] + (step[i] - means[i]) / (stepsTaken);
            state.getMeans().setEntry(i, value);
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double cov = (step[i] - means[i])  * (step[j] - means[j]) ;
                state.getCovariances().setEntry(i, j, (state.getCovariances().element(i, j)
                        + (cov - state.getCovariances().element(i, j)) / stepsTaken) * scale);
                if (i == j) {
                    // to avoid potentially obtaining a singular matrix
                    state.getCovariances().setEntry(i, j, state.getCovariances().element(i, j) + 0.001);
                    // the variances that are used when sampling from the multivariate distribution are along the
                    // diagonal and we want these relatively large to have good mixing.
                    //covariances[i][j] = Math.pow(sDev*means[i]/100.0,2); //1.0;
                }
            }
        }

        TruncatedMultivariateNormalDistribution tmvn = new TruncatedMultivariateNormalDistribution(state.getMeans(),
                state.getCovariances(),
                lower, upper);
        Vector proposal = tmvn.sample();

        java.text.DecimalFormat df = new java.text.DecimalFormat("0.#####E0");
        beta = Double.valueOf(df.format(proposal.element(0)));
        sigma = Double.valueOf(df.format(proposal.element(1)));
        gamma = Double.valueOf(df.format(proposal.element(2)));
        alpha = Double.valueOf(df.format(proposal.element(3)));
        alphaPrime = Double.valueOf(df.format(proposal.element(4)));
        testSensitivity = Double.valueOf(df.format(proposal.element(5)));
        mutationRate = Double.valueOf(df.format(proposal.element(6)));
        if (settings.getBadgerLifetimeModelled()) {
            infectedBadgerLifetime = Double.valueOf( df.format( proposal.element( 7 ) ) );
        }

    }

    public void generateParametersFile(final boolean initialise) {
        try (FileWriter writer = new FileWriter( settings.getParametersFile() );
             PrintWriter pw = new PrintWriter( writer )) {

            if (initialise) {
                this.beta = settings.getBetaDist().sample();
                this.sigma = settings.getSigmaDist().sample();
                this.gamma = settings.getGammaDist().sample();
                this.alpha = settings.getAlphaDist().sample();
                this.alphaPrime = settings.getAlphaPrimeDist().sample();
                this.testSensitivity = settings.getTestSensitivityDist().sample();
                this.mutationRate = settings.getMutationRateDist().sample();
                if (settings.getBadgerLifetimeModelled()) {
                    this.infectedBadgerLifetime = settings.getInfectedBadgerLifetimeDist().sample();
                }
            }

            pw.printf( "beta = %g%n", beta );
            pw.printf( "sigma = %g%n", sigma);
            pw.printf( "gamma = %g%n", gamma);
            pw.printf( "alpha = %g%n", alpha);
            pw.printf( "alphaPrime = %g%n", alphaPrime);
            pw.printf( "testSensitivity = %g%n", testSensitivity);
            pw.printf( "mutationRate = %g%n", mutationRate);
            if (settings.getBadgerLifetimeModelled()) {
                pw.printf( "infectedBadgerLifetime = %g%n", infectedBadgerLifetime);
            }

        } catch (IOException e) {
            throw new BroadwickException( "Could not generate parameters. " + e.getLocalizedMessage() );
        }
    }

    public boolean doParametersExist() {
        return (!beta.isNaN()) && (!sigma.isNaN()) && (!gamma.isNaN()) && (!alpha.isNaN()) && (!alphaPrime.isNaN())
                && (!testSensitivity.isNaN()) && (!mutationRate.isNaN()) && (!infectedBadgerLifetime.isNaN());
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder( 10 );
        sb.append(beta).append(",");
        sb.append(sigma).append(",");
        sb.append(gamma).append(",");
        sb.append(alpha).append(",");
        sb.append(alphaPrime).append(",");
        sb.append(testSensitivity).append(",");
        sb.append(mutationRate);
        if (settings.getBadgerLifetimeModelled()) {
            sb.append(",").append(infectedBadgerLifetime);
        }
        return sb.toString();
    }

    private double[] AddToArray(double[] oldArray, double newString)
    {
        double[] newArray = java.util.Arrays.copyOf(oldArray, oldArray.length+1);
        newArray[oldArray.length] = newString;
        return newArray;
    }

    private ProjectSettings settings;
    @Getter
    private Double beta;
    @Getter
    private Double sigma;
    @Getter
    private Double gamma;
    @Getter
    private Double alpha;
    @Getter
    private Double alphaPrime;
    @Getter
    private Double testSensitivity;
    @Getter
    private Double mutationRate;
    @Getter
    private Double infectedBadgerLifetime;

    private final Vector lower;
    private final Vector upper;

}
