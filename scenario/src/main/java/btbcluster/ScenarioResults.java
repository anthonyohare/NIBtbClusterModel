package btbcluster;

import broadwick.BroadwickException;
import broadwick.statistics.distributions.IntegerDistribution;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

public class ScenarioResults implements Serializable {

    public ScenarioResults(final String id) {
        this.id = id;
        this.reactorsAtBreakdownDistribution = new IntegerDistribution();
        this.snpDistanceDistribution = new IntegerDistribution();
    }

    public void recordCowBadgerTransmission() {
        numCowBadgerTransmissions += 1;
    }

    public void recordBadgerCowTransmission() {
        numBadgerCowTransmissions += 1;
    }

    public void recordCowCowTransmission() {
        numCowCowTransmissions += 1;
    }

    public void recordNumInfectedAnimalsMoved(final int numMoved) {
        numInfectedAnimalsMoved += numMoved;
    }

    public void recordNumberOfDetectedAnimalsAtSlaughter(final int numSlaughtered) {
        numDetectedAnimalsAtSlaughter += numSlaughtered;
    }

    public void recordNumberOfUndetectedAnimalsAtSlaughter(final int numSlaughtered) {
        numUndetectedAnimalsAtSlaughter += numSlaughtered;
    }

    public void recordNumberOfSamplesTaken(final int numSampled) {
        numSamplesTaken += numSampled;
    }

    public void recordReactors(int size) {
        numReactors += size;
        numBreakdowns += 1;
        reactorsAtBreakdownDistribution.setFrequency( size );
    }

    @Override
    public String toString() {
        final double centage = (100.0) / (numCowCowTransmissions + numCowBadgerTransmissions + numBadgerCowTransmissions);
        final StringBuilder sb = new StringBuilder( 10 );
        sb.append( "\tid = " ).append( id ).append( "\n" );
        sb.append( "\tnumCowCowTransmissions = " ).append( numCowCowTransmissions )
                .append( " (" ).append( numCowCowTransmissions * centage ).append( ")\n" );
        sb.append( "\tnumCowBadgerTransmissions = " ).append( numCowBadgerTransmissions )
                .append( " (" ).append( numCowBadgerTransmissions * centage ).append( ")\n" );
        sb.append( "\tnumBadgerCowTransmissions = " ).append( numBadgerCowTransmissions )
                .append( " (" ).append( numBadgerCowTransmissions * centage ).append( ")\n" );
        sb.append( "\tnumReactors = " ).append( numReactors ).append( "\n" );
        sb.append( "\tnumBreakdowns = " ).append( numBreakdowns ).append( "\n" );
        sb.append( "\tnumInfectedCows = " ).append( infectedCows.size() ).append( "\n" );
        sb.append( "\tnumSamplesTaken = " ).append( numSamplesTaken ).append( "\n" );
        sb.append( "\tnumDetectedAnimalsAtSlaughter = " ).append( numDetectedAnimalsAtSlaughter ).append( "\n" );
        sb.append( "\tnumUndetectedAnimalsAtSlaughter = " ).append( numUndetectedAnimalsAtSlaughter ).append( "\n" );
        sb.append( "\tnumInfectedAnimalsMoved = " ).append( numInfectedAnimalsMoved ).append( "\n" );
        sb.append( "\tloglikelihood = " ).append( loglikelihood ).append( "\n" );
        sb.append( "\tsnpDistanceDistribution = " ).append( snpDistanceDistribution.toString() ).append( "\n" );
        sb.append( "\treactorsAtBreakdownDistribution = " ).append( reactorsAtBreakdownDistribution.toString() ).append( "\n" );
        return sb.toString();
    }

    public void save() {
        final String jsonFile = String.format( "scenario_%s.results", id );
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue( new File( jsonFile ), this );

        } catch (IOException ex) {
            throw new BroadwickException( "Error saving arm as json; " + ex.getLocalizedMessage() );
        }
    }

    @Getter
    private int numCowCowTransmissions = 0;
    @Getter
    private int numCowBadgerTransmissions = 0;
    @Getter
    private int numBadgerCowTransmissions = 0;
    @Getter
    private int numReactors = 0;
    @Getter
    private int numBreakdowns = 0;
    @Getter
    private int numDetectedAnimalsAtSlaughter = 0;
    @Getter
    private int numUndetectedAnimalsAtSlaughter = 0;
    @Getter
    private int numInfectedAnimalsMoved = 0;
    @Getter
    private int numSamplesTaken = 0;
    @Setter
    @Getter
    private double loglikelihood;
    @Getter
    @JsonIgnore
    private Collection<InfectedCow> infectedCows = new ArrayList<>();
    @Getter
    @JsonSerialize(using = CustomObjectSerializer.class)
    private InfectionTree infectionTree = new InfectionTree();
    @Getter
    @JsonSerialize(using = CustomIntegerDistributionSerializer.class)
    private IntegerDistribution reactorsAtBreakdownDistribution;
    @Getter
    @JsonSerialize(using = CustomIntegerDistributionSerializer.class)
    private IntegerDistribution snpDistanceDistribution;
    private String id;

    private static final long serialVersionUID = 201903290000000001L;
}

class CustomObjectSerializer extends StdSerializer<Object> {

    public CustomObjectSerializer() {
        this( null );
    }

    public CustomObjectSerializer(Class<Object> o) {
        super( o );
    }

    @Override
    public void serialize(Object value, JsonGenerator gen,
                          SerializerProvider provider) throws IOException {
        gen.writeString( value.toString() );

    }

    private static final long serialVersionUID = 201903290000000001L;
}

class CustomIntegerDistributionSerializer extends StdSerializer<Object> {

    public CustomIntegerDistributionSerializer() {
        this( null );
    }

    public CustomIntegerDistributionSerializer(Class<Object> o) {
        super( o );
    }

    @Override
    public void serialize(Object value, JsonGenerator gen,
                          SerializerProvider provider) throws IOException {
        IntegerDistribution dist = ((IntegerDistribution) value);
        final StringBuilder sb = new StringBuilder();

        Iterator<Integer> it1 = dist.getBins().iterator();
        Iterator<Integer> it2 = dist.getBinContents().iterator();
        while(it1.hasNext() && it2.hasNext()) {
            sb.append(it1.next()).append(":").append(it2.next()).append(",");
        }
        gen.writeString( sb.toString() );

    }

    private static final long serialVersionUID = 201903290000000001L;
}