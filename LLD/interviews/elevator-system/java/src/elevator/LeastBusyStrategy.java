package elevator;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Spreads load: the car with the fewest pending stops, ties broken by distance. */
public final class LeastBusyStrategy implements ElevatorSelectionStrategy {
    @Override
    public Optional<Elevator> select(List<Elevator> elevators, Command.HallCall call) {
        return elevators.stream()
                .filter(Elevator::inService)
                .min(Comparator.comparingInt(Elevator::pendingStops)
                        .thenComparingInt(e -> Math.abs(e.floor() - call.floor()))
                        .thenComparingInt(Elevator::id));
    }
}
