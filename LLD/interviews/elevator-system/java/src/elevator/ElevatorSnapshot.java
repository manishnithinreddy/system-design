package elevator;

import java.util.List;

/** Immutable view of one elevator, safe to hand to other threads (displays, dashboards). */
public record ElevatorSnapshot(int id, int floor, Direction direction, ElevatorStatus status,
                               List<Integer> upStops, List<Integer> downStops) {}
