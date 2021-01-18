package btbcluster;

import broadwick.BroadwickException;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;


/**
 * Encompass a wildlife reservoir for bovine TB. The reservoir contains infected badgers.
 */
@Slf4j
public class Sett implements Serializable {

    /**
     * Create a new sett with an id and set of farms it is connected to.
     *
     * @param id             the id of the sett.
     * @param connectedFarms the list of farms it is connected to.
     */
    public Sett(final String id, Collection<Farm> connectedFarms) {
        this.id = id;
        this.connectedFarms = new ArrayList<>( connectedFarms );
        this.infectedBadgers = new ArrayList<>();
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        connectedFarms.clear();
    }

    @Override
    public String toString() {
        return id;
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

        return ((Sett) obj).getId().equals( id );
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

    @Getter
    private final String id;
    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    @JsonIdentityReference(alwaysAsId = true)
    @Getter
    private Collection<Farm> connectedFarms;
    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    @JsonIdentityReference(alwaysAsId = true)
    @Getter
    private Collection<InfectedBadger> infectedBadgers;
    private static final long serialVersionUID = 201903290000000001L;
}
