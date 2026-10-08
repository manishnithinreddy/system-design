package elevator;

/** Observer: floor displays, logging, metrics. Called on the simulation thread; must be fast. */
@FunctionalInterface
public interface ElevatorListener {
    void onEvent(ElevatorEvent event);
}
