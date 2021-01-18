package btbcluster;

import broadwick.BroadwickException;
import broadwick.math.Matrix;
import broadwick.math.Vector;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.Arrays;

@Slf4j
public class ProjectState {

    public ProjectState(final String name) {

        this.stateFilename = name;

        readState();
    }

    private void readState() {

        final File stateFile = new File( stateFilename );

        if (stateFile.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                final JsonNode jsonNode = mapper.readTree( new File( stateFilename ) );
                if (jsonNode == null || jsonNode.isEmpty()) {
                    // the state file was empty, this is ok in some circumstances, just create it here.
                    createState();
                } else {
                    this.proposedStep = jsonNode.get( "proposedStep" ).asText();
                    this.currentStep = jsonNode.get( "currentStep" ).asText();
                    this.logLikelihood = jsonNode.get( "logLikelihood" ).asDouble();
                    this.numSteps = jsonNode.get( "numSteps" ).asInt();
                    this.numAcceptedSteps = jsonNode.get( "numAcceptedSteps" ).asInt();
                    this.lastStepAccepted = jsonNode.get( "lastStepAccepted" ).asBoolean();
                    this.rngSeed = jsonNode.get( "rngSeed" ).asInt();
                    this.means = new Vector( Arrays.stream( jsonNode.get( "means" ).asText().split( "," ) )
                            .mapToDouble( Double::valueOf )
                            .toArray() );

                    final int n = means.length();
                    this.covariances = new Matrix( n, n );
                    final double[] cov = Arrays.stream( jsonNode.get( "covariances" ).asText().split( "," ) )
                            .mapToDouble( Double::valueOf )
                            .toArray();
                    int count = 0;
                    for (int row = 0; row < n; row++) {
                        for (int col = 0; col < n; col++) {
                            covariances.setEntry( row, col, cov[count++] );
                        }
                    }
                }
            } catch (IOException e) {
                log.error( "Error {}", e.getLocalizedMessage() );
                throw new BroadwickException( e );
            }
        } else {
            // the state file doesn't exist, this is ok in some circumstances, just create it here.
            createState();
        }
    }

    private void createState() {
        proposedStep = "";
        currentStep = "";
        logLikelihood = Double.NEGATIVE_INFINITY;
        numSteps = 0;
        numAcceptedSteps = 0;
        lastStepAccepted = false;
        rngSeed = 0;
        means = new Vector(7);
        covariances = new Matrix(7,7);
        save();
    }

    public void save() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue( new File( stateFilename ), this );

        } catch (IOException ex) {
            throw new BroadwickException( "Error saving arm as json; " + ex.getLocalizedMessage() );
        }
    }


private final String stateFilename;
    @Getter
    @Setter
    private String proposedStep;
    @Getter
    @Setter
    private String currentStep;
    @Getter
    @Setter
    private double logLikelihood;
    @Getter
    @Setter
    private int numSteps;
    @Getter
    @Setter
    private int numAcceptedSteps;
    @Getter
    @Setter
    private boolean lastStepAccepted;
    @Getter
    @Setter
    private int rngSeed;
    @Getter
    @Setter
    @JsonSerialize(using = CustomVectorSerializer.class)
    private Vector means;
    @Getter
    @Setter
    @JsonSerialize(using = CustomMatrixSerializer.class)
    private Matrix covariances;
}

class CustomVectorSerializer extends StdSerializer<Object> {

    public CustomVectorSerializer() {
        this( null );
    }

    public CustomVectorSerializer(Class<Object> o) {
        super( o );
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {

        Vector vec = ((Vector) value);

        int iMax = vec.length() - 1;
        if (iMax == -1) {
            gen.writeString( "" );
            return;
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            sb.append(vec.element(i));
            if (i == iMax)
                break;
            sb.append(",");
        }

        gen.writeString( sb.toString() );
    }

    private static final long serialVersionUID = 201903290000000001L;
}


class CustomMatrixSerializer extends StdSerializer<Object> {

    public CustomMatrixSerializer() {
        this( null );
    }

    public CustomMatrixSerializer(Class<Object> o) {
        super( o );
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {

        Matrix mat = ((Matrix) value);

        double[] flatArray = Arrays.stream(mat.toArray())
                .flatMapToDouble(Arrays::stream)
                .toArray();

        int iMax = flatArray.length - 1;
        if (iMax == -1) {
            gen.writeString( "" );
            return;
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            sb.append(flatArray[i]);
            if (i == iMax)
                break;
            sb.append(",");
        }

        gen.writeString( sb.toString() );
    }

    private static final long serialVersionUID = 201903290000000001L;
}