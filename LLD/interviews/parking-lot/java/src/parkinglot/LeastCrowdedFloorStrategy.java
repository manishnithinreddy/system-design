package parkinglot;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Spreads vehicles across floors: try the floor with the most free spots of a fitting size first.
 * Useful when one floor's ramp gets congested. Same interface, so ParkingLot doesn't change.
 */
public final class LeastCrowdedFloorStrategy implements SpotAllocationStrategy {
    @Override
    public Optional<ParkingSpot> allocate(List<ParkingFloor> floors, VehicleType type) {
        for (SpotSize size : type.fitsIn()) {
            List<ParkingFloor> byFreeDesc = floors.stream()
                    .sorted(Comparator.comparingInt((ParkingFloor f) -> f.freeCount(size)).reversed())
                    .toList();
            for (ParkingFloor floor : byFreeDesc) {
                // The count may be stale by now; claimSpot is the atomic step, so we just try.
                Optional<ParkingSpot> spot = floor.claimSpot(size);
                if (spot.isPresent()) return spot;
            }
        }
        return Optional.empty();
    }
}
