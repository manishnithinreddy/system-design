package elevator;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the car with the lowest estimated travel cost to the caller:
 *  - idle car: distance
 *  - car already heading toward the caller in the SAME direction the caller wants: distance
 *  - otherwise: it must finish its sweep first: (sweepEnd - here) + |sweepEnd - caller|
 * Pending stops are added as a small penalty (each stop costs time for doors).
 */
public final class NearestCarStrategy implements ElevatorSelectionStrategy {
    @Override
    public Optional<Elevator> select(List<Elevator> elevators, Command.HallCall call) {
        return elevators.stream()
                .filter(Elevator::inService)
                .min(Comparator.comparingInt((Elevator e) -> cost(e, call)).thenComparingInt(Elevator::id));
    }

    static int cost(Elevator e, Command.HallCall call) {
        int here = e.floor(), target = call.floor();
        int travel;
        if (e.direction() == Direction.IDLE) {
            travel = Math.abs(here - target);
        } else if (e.direction() == Direction.UP && call.direction() == Direction.UP && target >= here
                || e.direction() == Direction.DOWN && call.direction() == Direction.DOWN && target <= here) {
            travel = Math.abs(target - here);  // on the way
        } else {
            int end = e.sweepEnd();
            travel = Math.abs(end - here) + Math.abs(end - target);
        }
        return travel + e.pendingStops();
    }
}
