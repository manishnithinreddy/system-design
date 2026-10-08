package parkinglot;

import java.util.List;
import java.util.Optional;

/** Strategy: decides WHICH free spot a vehicle gets. Must claim the spot atomically. */
public interface SpotAllocationStrategy {
    Optional<ParkingSpot> allocate(List<ParkingFloor> floors, VehicleType type);
}
