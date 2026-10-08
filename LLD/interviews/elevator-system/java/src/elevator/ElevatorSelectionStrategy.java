package elevator;

import java.util.List;
import java.util.Optional;

/** Strategy: which car answers a hall call. Must skip cars in maintenance. */
public interface ElevatorSelectionStrategy {
    Optional<Elevator> select(List<Elevator> elevators, Command.HallCall call);
}
