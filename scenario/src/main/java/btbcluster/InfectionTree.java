package btbcluster;

import broadwick.BroadwickException;
import lombok.Getter;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

public class InfectionTree implements Serializable {

    public InfectionTree() {
        tree = new HashMap<>();
        this.root = new Infection() {
            @Override
            public String getId() {
                return null;
            }
        };
        tree.put( root, new HashSet<>() );
    }

    public InfectionTree(final Infection root) {
        this.root = root;
        tree = new HashMap<>();
        tree.put( root, new HashSet<>() );
    }

    public void insert(final Infection parent, final Infection child) {
        tree.get( parent ).add( child );
        tree.put( child, new HashSet<>() );
    }

    public void remove(final Infection node) {
        // find the node, and for each child, add it to the nodes parent, then remove the node,
        final Collection<Infection> children = tree.get( node );
        Infection parent = null;
        for (Map.Entry<Infection, Collection<Infection>> entry : tree.entrySet()) {
            if (entry.getValue().contains( node )) {
                parent = entry.getKey();
                break;
            }
        }
        if (parent == null) {
            throw new BroadwickException( "Could not find parent of " + node + " in infection tree" );
        }
        tree.get( parent ).addAll( children );
        tree.get( parent ).remove( node );
        tree.remove( node );
    }

    public Collection<InfectedCow> getInfectedCows() {
        return tree.keySet().stream()
                .filter( k -> (k instanceof InfectedCow) )
                .map( InfectedCow.class::cast )
                .collect( Collectors.toList() );
    }

    public String toString() {
        return tree.toString();
    }

    @Getter
    private Infection root;
    private Map<Infection, Collection<Infection>> tree;
    private static final long serialVersionUID = 201903290000000001L;
}
