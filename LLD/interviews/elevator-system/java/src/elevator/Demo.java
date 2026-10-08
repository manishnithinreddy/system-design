package elevator;

/** A few seconds of a 2-car, 10-floor building, printed tick by tick. */
public final class Demo {
    public static void main(String[] args) {
        ElevatorSystem building = new ElevatorSystem(2, 0, 9, new NearestCarStrategy());
        building.addListener(e -> {
            if (e.type() == ElevatorEvent.Type.DOORS_OPENED) {
                System.out.printf("   tick %2d: car %d opens doors at floor %d%n", e.tick(), e.elevatorId(), e.floor());
            }
        });

        System.out.println("t=0: someone on floor 6 presses DOWN, someone in car 0 presses 3");
        building.callElevator(6, Direction.DOWN);
        building.pressFloor(0, 3);
        for (int t = 1; t <= 4; t++) building.tick();

        System.out.println("t=4: someone on floor 1 presses UP, car 1 passenger presses 9");
        building.callElevator(1, Direction.UP);
        building.pressFloor(1, 9);
        while (!building.isQuiet()) building.tick();

        building.snapshots().forEach(s ->
                System.out.printf("end: car %d at floor %d, %s%n", s.id(), s.floor(), s.status()));
    }
}
