package btbcluster;

import broadwick.BroadwickException;
import broadwick.io.FileInput;
import broadwick.io.FileInputIterator;
import broadwick.statistics.Samples;
import broadwick.statistics.distributions.IntegerDistribution;
import broadwick.statistics.distributions.MultinomialDistribution;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
public class ControllerResults {

    public ControllerResults() {

    }

    public String toString() {
        final StringBuilder sb = new StringBuilder( 10 );
        sb.append( "numCowCowTransmissions = " ).append( numCowCowTransmissions.getSummary()).append("\n");
        sb.append( "numCowBadgerTransmissions = " ).append( numCowBadgerTransmissions.getSummary()).append("\n");
        sb.append( "numBadgerCowTransmissions = " ).append( numBadgerCowTransmissions.getSummary()).append("\n");
        sb.append( "numReactors = " ).append( numReactors.getSummary()).append("\n");
        sb.append( "numBreakdowns = " ).append( numBreakdowns.getSummary()).append("\n");
        sb.append( "numDetectedAnimalsAtSlaughter = " ).append( numDetectedAnimalsAtSlaughter.getSummary()).append("\n");
        sb.append( "numUndetectedAnimalsAtSlaughter = " ).append( numUndetectedAnimalsAtSlaughter.getSummary()).append("\n");
        sb.append( "numInfectedAnimalsMoved = " ).append( numInfectedAnimalsMoved.getSummary()).append("\n");
        if (logLikelihood.getSize() == 0) {
            sb.append( "logLikelihood = -Infinity \t -Infinity" ).append( "\n" );
        } else {
            sb.append( "logLikelihood = " ).append( logLikelihood.getSummary() ).append( "\n" );
            // We could use
            //sb.append( "logLikelihood = " ).append( calculateLikelihood() ).append( "\n" );
            // but we don't have error bars in this case. 
        }
        sb.append( "reactorsAtBreakdown = " ).append( asCsv(reactorsAtBreakdownDistribution)).append("\n");
        sb.append( "snpDistance = " ).append( asCsv(snpDistanceDistribution)).append("\n");

        return sb.toString();
    }

    public void addReactorsAtBreakdownDistribution(final String distString) {
        updateDistribution(reactorsAtBreakdownDistribution, distString);
    }

    public void addSnpDistanceDistribution(final String distString) {
        if (!distString.isEmpty()) {
            updateDistribution( snpDistanceDistribution, distString );
        }
    }

    private void updateDistribution(final Map<Integer,Samples> dist, final String distString) {
        for (String binString : distString.split( "," )) {
            final String[] split = binString.split( ":" );
            int bin = Integer.parseInt(split[0].trim());
            int value = Integer.parseInt(split[1].trim());
            Samples samples = dist.get( bin );
            if (samples == null) {
                samples = new Samples();
            }
            samples.add( value );
            dist.put( bin, samples );
        }
    }

    private String asCsv(final Map<Integer,Samples> dist) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Samples> entry : dist.entrySet()) {
            sb.append(entry.getKey()).append(":");
            sb.append(entry.getValue().getMean()).append(",");
        }
        return sb.toString();
    }



    /**
     * Calculate the Monte Carlo score (the log-likelihood) for the simulation. We could use the mean values from
     * each simulation as an alternative.
     *
     * @return the log-likelihood.
     */
    private double calculateLikelihood() {
        // Observed SNP distribution
        IntegerDistribution observedSnpDistribution = new IntegerDistribution();
        try (FileInput fileInput = new FileInput( "HannahsPhyloTree_pairwise_distances_histogram.csv" )) {
            final FileInputIterator iterator = fileInput.iterator();
            while (iterator.hasNext()) {
                final String line = iterator.next();
                if (line != null) {
                    final String[] split = line.split( ":" );
                    final int x = Integer.parseInt( split[0].trim() );
                    final int frequency = Integer.parseInt( split[1].trim() );
                    observedSnpDistribution.setFrequency( x, frequency );
                }
            }
        } catch (IOException e) {
            log.error( "Could not read distribution from {}", "HannahsPhyloTree_pairwise_distances_histogram.csv" );
            throw new BroadwickException( "Failed to read observed SNP distribution distribution" );
        }

        // Simulated SNP distriution
        IntegerDistribution simulatedSnpDistribution = new IntegerDistribution();
        for (Map.Entry<Integer, Samples> entry  : snpDistanceDistribution.entrySet()) {
            simulatedSnpDistribution .setFrequency( entry.getKey(), ((int)entry.getValue().getMean()) );
        }


        // Calculate the log-likelihood as
        // L = log(Factorial( N )) + sum(x_i*log(p_i)) - sum(log(x_i))
        // where p_i = the probability of observing i SNP differences (from observed differences)
        //       x_i = the number of times we observe i SNP differences in our simulation
        //       N = the total number of observed SNP differences
        // p_i = observedPairwiseDistancesDistribution.getFrequency( i )/n;
        // x_i = simulatededPairwiseDistancesDistribution.getFrequency( i );

        final double[] probabilities = new double[observedSnpDistribution.getNumBins()];
        final int[] x = new int[observedSnpDistribution.getNumBins()];
        final int total = observedSnpDistribution.getSumCounts();
        {
            int i = 0;
            for (Integer bin : observedSnpDistribution.getBins()) {
                x[i] = observedSnpDistribution.getFrequency( bin );
                probabilities[i] = (1.0 * x[i]) / total;
                i++;
            }
        }

        MultinomialDistribution dist = new MultinomialDistribution(total, probabilities);
        if (dist != null) {
            final int expectedCount = observedSnpDistribution.getSumCounts();

            // If I want to match the observed distribution exactly then the bins in the pairwise distribution
            // should be the same as the observed distribution then I need to include the following statement
            if (simulatedSnpDistribution.getNumBins() > observedSnpDistribution.getNumBins()) {
                log.warn("Could not calculate likelihood - the max SNP differences of the observed dists do not match the calculated {}.", simulatedSnpDistribution.toCsv());
                return Double.NEGATIVE_INFINITY;
            }
            // else - I don't care - just ignore the SNP differences that are too large.

            final IntegerDistribution generatedPairwiseDistancesDistribution = new IntegerDistribution(observedSnpDistribution.getNumBins());
            for (int bin : generatedPairwiseDistancesDistribution.getBins()) {
                Integer data = simulatedSnpDistribution.getFrequency(bin);
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
                        generatedPairwiseDistancesDistribution.toCsv(), observedSnpDistribution.toCsv());
                throw new IllegalArgumentException(String.format("Multinomial distribution error: Sum_x [%d] != number of samples [%d].", sumX, dist.getN()));
            } else {
                final double logLikelihood = broadwick.math.Factorial.lnFactorial(dist.getN()) - sumXFact + sumPX;
                log.debug("logLikelihood : {}", logLikelihood);
                return logLikelihood;
            }
        }
        return Double.NEGATIVE_INFINITY;
    }





    @Getter
    private Samples numCowCowTransmissions = new Samples();
    @Getter
    private Samples numCowBadgerTransmissions = new Samples();
    @Getter
    private Samples numBadgerCowTransmissions = new Samples();
    @Getter
    private Samples numReactors = new Samples();
    @Getter
    private Samples numBreakdowns = new Samples();
    @Getter
    private Samples numDetectedAnimalsAtSlaughter = new Samples();
    @Getter
    private Samples numUndetectedAnimalsAtSlaughter = new Samples();
    @Getter
    private Samples numInfectedAnimalsMoved = new Samples();
    @Getter
    private Samples logLikelihood = new Samples();
    @Getter
    private Map<Integer,Samples> reactorsAtBreakdownDistribution  = new TreeMap<>();
    @Getter
    private Map<Integer,Samples> snpDistanceDistribution  = new TreeMap<>();
}
