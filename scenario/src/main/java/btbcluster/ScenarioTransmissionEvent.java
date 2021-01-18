package btbcluster;

import broadwick.stochastic.SimulationEvent;
import broadwick.stochastic.SimulationState;
import lombok.Getter;

public class ScenarioTransmissionEvent extends SimulationEvent {

    /**
     * Construct a new event from the initial state a final state.
     * @param initialState the name and index of the bin of the initial state.
     * @param finalState   the name and index of the bin of the final state.
     * @param farm the farm on which the event took place.
     */
    public ScenarioTransmissionEvent(final SimulationState initialState, final SimulationState finalState, final Farm farm) {
        super(initialState, finalState);
        this.initialState = initialState;
        this.finalState = finalState;
        this.farm = farm;
    }

    @Getter
    private final SimulationState initialState;
    @Getter
    private final SimulationState finalState;
    @Getter
    private final Farm farm;
    private static final long serialVersionUID = 201903290000000001L;
}
