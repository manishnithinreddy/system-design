package parkinglot;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An entity: it has identity (floor + number) and state that changes (who is parked here).
 * That's why it's a class, not a record.
 */
public final class ParkingSpot implements Comparable<ParkingSpot> {
    private final int floor;
    private final int number;
    private final SpotSize size;
    private final AtomicReference<Vehicle> occupant = new AtomicReference<>();

    public ParkingSpot(int floor, int number, SpotSize size) {
        this.floor = floor;
        this.number = number;
        this.size = size;
    }

    /** Atomically claims the spot. Returns false if someone else already holds it. */
    boolean occupy(Vehicle vehicle) {
        return occupant.compareAndSet(null, vehicle);
    }

    void release() {
        occupant.set(null);
    }

    public boolean isFree() {
        return occupant.get() == null;
    }

    public int floor() { return floor; }
    public int number() { return number; }
    public SpotSize size() { return size; }

    public String id() {
        return "F" + floor + "-" + size.name().charAt(0) + number;
    }

    /** Lower number = closer to the entrance/lift. Used to keep free spots sorted. */
    @Override
    public int compareTo(ParkingSpot other) {
        return Integer.compare(number, other.number);
    }

    @Override
    public String toString() {
        return id();
    }
}
