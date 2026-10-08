package elevator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * One elevator car, scheduled with the LOOK algorithm:
 *   keep moving in the current direction while there are stops ahead; then reverse; idle when none.
 *
 * Stops are split by the direction in which they should be SERVED:
 *   upStops   : car calls above us + hall calls where someone pressed UP
 *   downStops : car calls below us + hall calls where someone pressed DOWN
 * So a person on floor 5 who pressed DOWN is not picked up while we pass 5 going UP.
 *
 * NOT thread-safe on purpose: only the simulation thread (ElevatorSystem.tick) touches it.
 */
public final class Elevator {
    private final int id;
    private final int minFloor, maxFloor;
    private int floor;
    private Direction direction = Direction.IDLE;
    private ElevatorStatus status = ElevatorStatus.IDLE;
    private final NavigableSet<Integer> upStops = new TreeSet<>();
    private final NavigableSet<Integer> downStops = new TreeSet<>();
    private final List<Command.HallCall> assignedHallCalls = new ArrayList<>();

    Elevator(int id, int minFloor, int maxFloor, int startFloor) {
        this.id = id;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.floor = startFloor;
    }

    // ---------- requests ----------

    void addCarCall(int target) {
        checkFloor(target);
        if (target > floor) upStops.add(target);
        else if (target < floor) downStops.add(target);
        else if (status != ElevatorStatus.MOVING) status = ElevatorStatus.DOORS_OPEN; // already here: reopen
    }

    void addHallCall(Command.HallCall call) {
        checkFloor(call.floor());
        assignedHallCalls.add(call);
        if (call.direction() == Direction.UP) upStops.add(call.floor());
        else downStops.add(call.floor());
    }

    /** Going into maintenance: drop car calls and hand back unserved hall calls for reassignment. */
    List<Command.HallCall> enterMaintenance() {
        List<Command.HallCall> unserved = new ArrayList<>(assignedHallCalls);
        assignedHallCalls.clear();
        upStops.clear();
        downStops.clear();
        direction = Direction.IDLE;
        status = ElevatorStatus.MAINTENANCE;
        return unserved;
    }

    void exitMaintenance() {
        status = ElevatorStatus.IDLE;
    }

    // ---------- one time step ----------

    /** Advance one tick: close doors, or serve this floor, or move one floor. */
    void step(long tick, Consumer<ElevatorEvent> emit) {
        if (status == ElevatorStatus.MAINTENANCE) return;
        if (status == ElevatorStatus.DOORS_OPEN) {     // doors stay open for exactly one tick
            status = hasStops() ? ElevatorStatus.MOVING : ElevatorStatus.IDLE;
            if (!hasStops()) direction = Direction.IDLE;
            return;
        }
        Integer target = nextTarget();
        if (target == null) {
            direction = Direction.IDLE;
            status = ElevatorStatus.IDLE;
            return;
        }
        if (target != floor) {
            direction = target > floor ? Direction.UP : Direction.DOWN;
            floor += direction == Direction.UP ? 1 : -1;
            status = ElevatorStatus.MOVING;
            emit.accept(new ElevatorEvent(tick, id, ElevatorEvent.Type.MOVED, floor));
        }
        if (serveCurrentFloor()) {
            status = ElevatorStatus.DOORS_OPEN;
            emit.accept(new ElevatorEvent(tick, id, ElevatorEvent.Type.DOORS_OPENED, floor));
        }
    }

    /** LOOK: the next floor we are heading to, or null if there's nothing to do. */
    Integer nextTarget() {
        switch (direction) {
            case UP -> {
                Integer ahead = upStops.ceiling(floor);
                if (ahead != null) return ahead;
                // no up-stops ahead, but someone above wants to go DOWN: go up to the highest one, then turn
                if (!downStops.isEmpty() && downStops.last() >= floor) return downStops.last();
                return turnAround(Direction.DOWN);
            }
            case DOWN -> {
                Integer ahead = downStops.floor(floor);
                if (ahead != null) return ahead;
                if (!upStops.isEmpty() && upStops.first() <= floor) return upStops.first();
                return turnAround(Direction.UP);
            }
            default -> { // IDLE: go to the nearest pending stop of either kind
                Integer best = null;
                for (Integer s : allStops()) {
                    if (best == null || Math.abs(s - floor) < Math.abs(best - floor)) best = s;
                }
                return best;
            }
        }
    }

    private Integer turnAround(Direction newDirection) {
        if (!hasStops()) return null;
        direction = newDirection;
        return nextTarget();
    }

    /** Open doors here if this floor is a stop for the way we're going (or our turnaround point). */
    private boolean serveCurrentFloor() {
        boolean servedUp = false, servedDown = false;
        if (direction == Direction.UP || direction == Direction.IDLE) {
            servedUp = upStops.remove(floor);
            if (upStops.higher(floor) == null && downStops.contains(floor)) { // top of the sweep: turn
                servedDown = downStops.remove(floor);
                if (servedDown && !servedUp) direction = Direction.DOWN;
            }
        }
        if (!servedUp && !servedDown && (direction == Direction.DOWN || direction == Direction.IDLE)) {
            servedDown = downStops.remove(floor);
            if (downStops.lower(floor) == null && upStops.contains(floor)) { // bottom of the sweep: turn
                servedUp = upStops.remove(floor);
                if (servedUp && !servedDown) direction = Direction.UP;
            }
        }
        if (servedUp) clearHallCall(Direction.UP);
        if (servedDown) clearHallCall(Direction.DOWN);
        return servedUp || servedDown;
    }

    private void clearHallCall(Direction d) {
        Iterator<Command.HallCall> it = assignedHallCalls.iterator();
        while (it.hasNext()) {
            Command.HallCall c = it.next();
            if (c.floor() == floor && c.direction() == d) it.remove();
        }
    }

    // ---------- read-only helpers (used by strategies and snapshots) ----------

    public int id() { return id; }
    public int floor() { return floor; }
    public Direction direction() { return direction; }
    public ElevatorStatus status() { return status; }
    public boolean inService() { return status != ElevatorStatus.MAINTENANCE; }
    public int pendingStops() { return upStops.size() + downStops.size(); }
    boolean hasStops() { return !upStops.isEmpty() || !downStops.isEmpty(); }

    /** Furthest floor this car will reach in its current sweep (used for cost estimates). */
    public int sweepEnd() {
        if (direction == Direction.UP) {
            int end = floor;
            if (!upStops.isEmpty()) end = Math.max(end, upStops.last());
            if (!downStops.isEmpty()) end = Math.max(end, downStops.last());
            return end;
        }
        if (direction == Direction.DOWN) {
            int end = floor;
            if (!downStops.isEmpty()) end = Math.min(end, downStops.first());
            if (!upStops.isEmpty()) end = Math.min(end, upStops.first());
            return end;
        }
        return floor;
    }

    ElevatorSnapshot snapshot() {
        return new ElevatorSnapshot(id, floor, direction, status, List.copyOf(upStops), List.copyOf(downStops));
    }

    private List<Integer> allStops() {
        List<Integer> all = new ArrayList<>(upStops);
        all.addAll(downStops);
        return all;
    }

    private void checkFloor(int f) {
        if (f < minFloor || f > maxFloor) throw new IllegalArgumentException("floor " + f + " out of range");
    }
}
