package btbcluster;

import broadwick.stochastic.SimulationState;
import broadwick.utils.CloneUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Set;

/**
 * A class to encapsulate the properties of an infected badger.
 */
@Slf4j
public class InfectedBadger implements Infection, SimulationState, Serializable {

    /**
     * Create a badger with a set of snps.
     *
     * @param id   the id of the infected badger.
     * @param snps the snps that should be associated with the badger.
     * @param day  the day the badger is added (this will be used to calculated new mutations)
     */
    public InfectedBadger(final String id, final Set<Integer> snps, final int day, final int dateInfected) {
        this.id = id;
        this.snps = CloneUtils.deepClone( snps );
        this.lastSnpGeneration = day;
        this.dateInfected = day; // the
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

        return ((InfectedBadger) obj).getId().equals( id );
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

    @Override
    public String getStateName() {
        return this.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder( 10 );
        sb.append( id ).append( ":" ).append( InfectionState.INFECTIOUS );
        return sb.toString();
    }

    @Getter
    private final String id;
    @Getter
    private int dateInfected;
    @Getter
    private Set<Integer> snps;
    @Getter
    @Setter
    private int lastSnpGeneration;
    private static final long serialVersionUID = 201903290000000001L;
}
