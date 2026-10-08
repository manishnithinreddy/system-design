package parkinglot;

import java.util.List;
import java.util.Optional;

/** Lowest floor first; on each floor the smallest fitting size, then the lowest-numbered spot. */
public final class NearestFirstStrategy implements SpotAllocationStrategy {
    @Override
    public Optional<ParkingSpot> allocate(List<ParkingFloor> floors, VehicleType type) {
        for (ParkingFloor floor : floors) {
            for (SpotSize size : type.fitsIn()) {
                Optional<ParkingSpot> spot = floor.claimSpot(size);
                if (spot.isPresent()) return spot;
            }
        }
        return Optional.empty();
    }
}
