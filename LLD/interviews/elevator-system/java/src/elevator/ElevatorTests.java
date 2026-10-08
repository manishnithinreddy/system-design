package elevator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class ElevatorTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        servesCarCallsInLookOrder();
        requestBehindIsServedAfterReversal();
        downCallIsNotPickedUpWhileGoingUp();
        idleCarGoesUpToADownCall();
        nearestIdleCarIsChosen();
        carAlreadyOnTheWayBeatsIdleCar();
        maintenanceCarIsSkippedAndItsCallsReassigned();
        callsWaitWhenEveryCarIsInMaintenance();
        concurrentButtonPressesAreAllServed();
        System.out.println("All " + passed + " tests passed.");
    }

    /** Runs ticks until quiet; returns floors where doors opened, per elevator id. */
    static List<String> run(ElevatorSystem sys, int maxTicks) {
        List<String> opened = Collections.synchronizedList(new ArrayList<>());
        sys.addListener(e -> { if (e.type() == ElevatorEvent.Type.DOORS_OPENED) opened.add(e.elevatorId() + "@" + e.floor()); });
        for (int i = 0; i < maxTicks; i++) {
            sys.tick();
            if (sys.isQuiet()) return opened;
        }
        throw new AssertionError("not quiet after " + maxTicks + " ticks: " + sys.snapshots());
    }

    static void servesCarCallsInLookOrder() {
        ElevatorSystem sys = new ElevatorSystem(1, 0, 10, new NearestCarStrategy());
        sys.pressFloor(0, 5); sys.pressFloor(0, 2); sys.pressFloor(0, 8);
        assertEquals(List.of("0@2", "0@5", "0@8"), run(sys, 100), "sorted by floor while going up, not by press order");
        pass("servesCarCallsInLookOrder");
    }

    static void requestBehindIsServedAfterReversal() {
        ElevatorSystem sys = new ElevatorSystem(1, 0, 10, new NearestCarStrategy());
        List<String> opened = new ArrayList<>();
        sys.addListener(e -> { if (e.type() == ElevatorEvent.Type.DOORS_OPENED) opened.add("" + e.floor()); });
        sys.pressFloor(0, 8);
        for (int i = 0; i < 4; i++) sys.tick();           // now around floor 4, going up
        sys.pressFloor(0, 2);                              // behind us
        while (!sys.isQuiet()) sys.tick();
        assertEquals(List.of("8", "2"), opened, "keep going up to 8 first, then come back for 2");
        pass("requestBehindIsServedAfterReversal");
    }

    static void downCallIsNotPickedUpWhileGoingUp() {
        ElevatorSystem sys = new ElevatorSystem(1, 0, 10, new NearestCarStrategy());
        sys.pressFloor(0, 9);
        sys.tick();                                        // moving up
        sys.callElevator(5, Direction.DOWN);               // someone at 5 wants to go DOWN
        assertEquals(List.of("0@9", "0@5"), run(sys, 100), "skip 5 on the way up, serve it on the way down");
        pass("downCallIsNotPickedUpWhileGoingUp");
    }

    static void idleCarGoesUpToADownCall() {
        ElevatorSystem sys = new ElevatorSystem(1, 0, 10, new NearestCarStrategy());
        sys.callElevator(7, Direction.DOWN);
        assertEquals(List.of("0@7"), run(sys, 100), "turnaround point is served");
        assertEquals(Direction.IDLE, sys.snapshots().get(0).direction(), "idle afterwards");
        pass("idleCarGoesUpToADownCall");
    }

    static void nearestIdleCarIsChosen() {
        ElevatorSystem sys = new ElevatorSystem(2, 0, 10, new NearestCarStrategy());
        sys.pressFloor(1, 10);                             // move car 1 to the top first
        run(sys, 100);
        sys.callElevator(8, Direction.UP);                 // car 0 at 0 (distance 8), car 1 at 10 (distance 2)
        assertEquals(List.of("1@8"), run(sys, 100), "car 1 is nearer");
        pass("nearestIdleCarIsChosen");
    }

    static void carAlreadyOnTheWayBeatsIdleCar() {
        ElevatorSystem sys = new ElevatorSystem(2, 0, 20, new NearestCarStrategy());
        sys.pressFloor(1, 15);
        for (int i = 0; i < 3; i++) sys.tick();            // car 1 at floor 3 heading up to 15; car 0 idle at 0
        sys.callElevator(6, Direction.UP);                 // car 1: 3 floors + 1 stop; car 0: 6 floors
        List<String> opened = run(sys, 100);
        assertEquals(List.of("1@6", "1@15"), opened, "car 1 picks it up on the way");
        pass("carAlreadyOnTheWayBeatsIdleCar");
    }

    static void maintenanceCarIsSkippedAndItsCallsReassigned() {
        ElevatorSystem sys = new ElevatorSystem(2, 0, 10, new NearestCarStrategy());
        sys.callElevator(3, Direction.UP);                 // both idle at 0, tie -> car 0
        sys.submit(new Command.SetMaintenance(0, true));   // car 0 breaks before arriving
        assertEquals(List.of("1@3"), run(sys, 100), "car 1 takes over the call");
        assertEquals(ElevatorStatus.MAINTENANCE, sys.snapshots().get(0).status(), "car 0 stays out");
        pass("maintenanceCarIsSkippedAndItsCallsReassigned");
    }

    static void callsWaitWhenEveryCarIsInMaintenance() {
        ElevatorSystem sys = new ElevatorSystem(1, 0, 10, new NearestCarStrategy());
        sys.submit(new Command.SetMaintenance(0, true));
        sys.callElevator(4, Direction.DOWN);
        for (int i = 0; i < 10; i++) sys.tick();
        assertEquals(0, sys.snapshots().get(0).floor(), "nothing moves");
        sys.submit(new Command.SetMaintenance(0, false));
        assertEquals(List.of("0@4"), run(sys, 100), "waiting call served once a car is back");
        pass("callsWaitWhenEveryCarIsInMaintenance");
    }

    /** 8 threads press 4,000 random buttons while the simulation thread ticks. Every hall-call floor must be visited. */
    static void concurrentButtonPressesAreAllServed() throws Exception {
        ElevatorSystem sys = new ElevatorSystem(4, 0, 30, new NearestCarStrategy());
        Set<Integer> requested = Collections.synchronizedSet(new HashSet<>());
        Set<Integer> opened = Collections.synchronizedSet(new HashSet<>());
        sys.addListener(e -> { if (e.type() == ElevatorEvent.Type.DOORS_OPENED) opened.add(e.floor()); });

        AtomicBoolean producersDone = new AtomicBoolean(false);
        Thread simulation = new Thread(() -> {
            while (!producersDone.get() || !sys.isQuiet()) sys.tick();
        });
        simulation.start();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService buttons = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                int seed = t;
                buttons.submit(() -> {
                    Random rnd = new Random(seed);
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        int floor = rnd.nextInt(31);
                        if (rnd.nextBoolean()) {
                            Direction d = floor == 30 ? Direction.DOWN : floor == 0 ? Direction.UP
                                    : rnd.nextBoolean() ? Direction.UP : Direction.DOWN;
                            requested.add(floor);
                            sys.callElevator(floor, d);
                        } else {
                            sys.pressFloor(rnd.nextInt(4), floor);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
        }
        producersDone.set(true);
        simulation.join(30_000);
        assertEquals(false, simulation.isAlive(), "simulation finished");
        assertTrue(opened.containsAll(requested), "every hall-call floor got a door opening");
        pass("concurrentButtonPressesAreAllServed");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
