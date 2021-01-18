package btbcluster;

import broadwick.BroadwickException;
import broadwick.stochastic.SimulationState;
import broadwick.utils.CloneUtils;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Set;

/**
 * A class to encapsulate the properties of an infected cow.
 */
@JsonIdentityInfo(
        generator = ObjectIdGenerators.PropertyGenerator.class,
        property = "id")
@Slf4j
public class InfectedCow implements Infection, SimulationState, Serializable {

    /**
     * Create a cow with a set of snps.
     *
     * @param id              the id of the infected cow.
     * @param snps            the snps that should be associated with the cow.
     * @param day             the day the badger is added (this will be used to calculated new mutations)
     * @param infectionStatus the initial infection status of the cow.
     */
    public InfectedCow(final String id, final Set<Integer> snps,
                       final int day, final InfectionState infectionStatus) {
        this.id = id;
        this.snps = CloneUtils.deepClone( snps );
        this.lastSnpGeneration = day;
        this.infectionStatus = infectionStatus;
        this.dateSampleTaken = -1;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        return ((InfectedCow) obj).getId().equals( id );
    }

    @Override
    public final String getStateName() {
        return this.toString();
    }

    @Override
    public final String toString() {
        final StringBuilder sb = new StringBuilder( 10 );
        sb.append( id ).append( ":" ).append( infectionStatus );
        return sb.toString();
    }

    /**
     * Create a json object of this class.
     *
     * @return a json formatted string.
     */
    public String asJson() {
        String jsonStr = "";

        try {
            ObjectMapper mapper = new ObjectMapper();
            jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString( this );
        } catch (JsonProcessingException jpe) {
            throw new BroadwickException( "Error saving arm as json; " + jpe.getLocalizedMessage() );
        }
        return jsonStr;
    }

    @Override
    protected void finalize() {
        try {
            snps.clear();
            super.finalize();
        } catch (Throwable t) {
            log.error( "Could not free Agent's memory. {}", t.getLocalizedMessage() );
        }
    }

    /**
     * Return a copy of this cow as a new object but with an id that signifies it is a copy
     *
     * @return a distinct copy of this cow.
     */
    public InfectedCow copyOf() {
        return new InfectedCow( id, snps, lastSnpGeneration, infectionStatus );
    }

    @Getter
    private final String id;
    @Getter
    private Set<Integer> snps;
    @Getter
    @Setter
    private int lastSnpGeneration;
    @Getter
    @Setter
    private int dateSampleTaken;
    @Getter
    @Setter
    private InfectionState infectionStatus;
    private static final long serialVersionUID = 201903290000000001L;
}
