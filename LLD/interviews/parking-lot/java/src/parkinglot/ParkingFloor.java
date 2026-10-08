package parkinglot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One floor. Keeps FREE spots per size in a sorted concurrent set.
 *
 * pollFirst() removes and returns the nearest free spot in ONE atomic step, so two entry
 * gates calling it at the same moment can never get the same spot.
 * Free counts are kept in separate AtomicIntegers because ConcurrentSkipListSet.size() is O(n).
 */
public final class ParkingFloor {
    private final int number;
    private final Map<SpotSize, ConcurrentSkipListSet<ParkingSpot>> freeSpots = new EnumMap<>(SpotSize.class);
    private final Map<SpotSize, AtomicInteger> freeCounts = new EnumMap<>(SpotSize.class);
    private final Map<SpotSize, Integer> capacity = new EnumMap<>(SpotSize.class);

    public ParkingFloor(int number, int small, int medium, int large) {
        this.number = number;
        int spotNumber = 1;
        spotNumber = addSpots(SpotSize.SMALL, small, spotNumber);
        spotNumber = addSpots(SpotSize.MEDIUM, medium, spotNumber);
        addSpots(SpotSize.LARGE, large, spotNumber);
    }

    private int addSpots(SpotSize size, int count, int firstNumber) {
        if (count < 0) throw new IllegalArgumentException("negative spot count");
        ConcurrentSkipListSet<ParkingSpot> set = new ConcurrentSkipListSet<>();
        for (int i = 0; i < count; i++) {
            set.add(new ParkingSpot(number, firstNumber + i, size));
        }
        freeSpots.put(size, set);
        freeCounts.put(size, new AtomicInteger(count));
        capacity.put(size, count);
        return firstNumber + count;
    }

    /** Atomically takes the nearest free spot of this size, if any. */
    Optional<ParkingSpot> claimSpot(SpotSize size) {
        ParkingSpot spot = freeSpots.get(size).pollFirst();
        if (spot == null) return Optional.empty();
        freeCounts.get(size).decrementAndGet();
        return Optional.of(spot);
    }

    void returnSpot(ParkingSpot spot) {
        freeSpots.get(spot.size()).add(spot);
        freeCounts.get(spot.size()).incrementAndGet();
    }

    public int number() { return number; }

    public int freeCount(SpotSize size) {
        return freeCounts.get(size).get();
    }

    public int capacity(SpotSize size) {
        return capacity.get(size);
    }
}
