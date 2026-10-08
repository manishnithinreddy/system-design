package elevator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Facade for the whole bank of elevators.
 *
 * Concurrency model, "single writer":
 *   - ANY thread may call submit() / callElevator() / pressFloor() (buttons are pressed concurrently).
 *     Those only put a Command on a thread-safe queue.
 *   - ONLY the simulation thread calls tick(). It drains the queue and is the only code that ever
 *     reads or changes elevator state. So the elevators themselves need no locks at all.
 *   - Other threads read state via snapshots(), an immutable list re-published after every tick.
 */
public final class ElevatorSystem {
    private final int minFloor, maxFloor;
    private final List<Elevator> elevators;
    private final ElevatorSelectionStrategy strategy;
    private final BlockingQueue<Command> inbox = new LinkedBlockingQueue<>();
    private final Deque<Command.HallCall> waiting = new ArrayDeque<>(); // no car available yet
    private final List<ElevatorListener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<ElevatorSnapshot> snapshots;
    private long tick = 0;

    public ElevatorSystem(int elevatorCount, int minFloor, int maxFloor, ElevatorSelectionStrategy strategy) {
        if (elevatorCount <= 0 || minFloor >= maxFloor) throw new IllegalArgumentException("bad building");
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.strategy = strategy;
        List<Elevator> cars = new ArrayList<>();
        for (int i = 0; i < elevatorCount; i++) cars.add(new Elevator(i, minFloor, maxFloor, minFloor));
        this.elevators = List.copyOf(cars);
        publishSnapshots();
    }

    // ---------- called from any thread ----------

    public void submit(Command command) {
        inbox.add(command);
    }

    public void callElevator(int floor, Direction direction) {
        submit(new Command.HallCall(floor, direction));
    }

    public void pressFloor(int elevatorId, int floor) {
        submit(new Command.CarCall(elevatorId, floor));
    }

    public void addListener(ElevatorListener listener) {
        listeners.add(listener);
    }

    public List<ElevatorSnapshot> snapshots() {
        return snapshots;
    }

    // ---------- called ONLY from the simulation thread ----------

    public void tick() {
        tick++;
        List<Command> batch = new ArrayList<>();
        inbox.drainTo(batch);
        for (Command c : batch) apply(c);
        retryWaiting();
        for (Elevator e : elevators) e.step(tick, this::emit);
        publishSnapshots();
    }

    /** True when there is nothing left to do: used by tests and demos. */
    public boolean isQuiet() {
        return inbox.isEmpty() && waiting.isEmpty()
                && elevators.stream().allMatch(e -> !e.hasStops() && e.status() != ElevatorStatus.DOORS_OPEN);
    }

    private void apply(Command command) {
        switch (command) {
            case Command.HallCall call -> {
                if (call.floor() < minFloor || call.floor() > maxFloor) return; // ignore invalid button
                assign(call);
            }
            case Command.CarCall call -> {
                Elevator e = elevators.get(call.elevatorId());
                if (e.inService() && call.floor() >= minFloor && call.floor() <= maxFloor) e.addCarCall(call.floor());
            }
            case Command.SetMaintenance m -> {
                Elevator e = elevators.get(m.elevatorId());
                if (m.on() && e.inService()) {
                    e.enterMaintenance().forEach(this::assign); // hand its passengers' calls to other cars
                    emit(new ElevatorEvent(tick, e.id(), ElevatorEvent.Type.MAINTENANCE_ON, e.floor()));
                } else if (!m.on() && !e.inService()) {
                    e.exitMaintenance();
                    emit(new ElevatorEvent(tick, e.id(), ElevatorEvent.Type.MAINTENANCE_OFF, e.floor()));
                }
            }
        }
    }

    private void assign(Command.HallCall call) {
        Optional<Elevator> car = strategy.select(elevators, call);
        if (car.isPresent()) car.get().addHallCall(call);
        else waiting.add(call); // every car is in maintenance: keep the call, retry next tick
    }

    private void retryWaiting() {
        int n = waiting.size();
        for (int i = 0; i < n; i++) assign(waiting.poll());
    }

    private void emit(ElevatorEvent event) {
        for (ElevatorListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (RuntimeException ex) {
                System.err.println("listener failed: " + ex); // a broken display must not stop the elevators
            }
        }
    }

    private void publishSnapshots() {
        snapshots = elevators.stream().map(Elevator::snapshot).toList();
    }
}
