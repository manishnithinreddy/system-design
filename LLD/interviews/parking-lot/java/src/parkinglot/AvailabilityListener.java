package parkinglot;

/** Observer: notified whenever free-spot counts change (e.g. LED boards at each floor). */
@FunctionalInterface
public interface AvailabilityListener {
    void onAvailabilityChanged(int floor, SpotSize size, int freeSpots);
}
