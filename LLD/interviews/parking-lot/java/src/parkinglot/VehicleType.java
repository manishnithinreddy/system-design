package parkinglot;

import java.util.List;

/**
 * Vehicle types and which spot sizes they fit in, smallest first,
 * so allocation naturally prefers the tightest fit and keeps big spots for big vehicles.
 *
 * An enum with data instead of a Car/Truck/Motorcycle class hierarchy: the types differ
 * only in DATA (what fits where), not in BEHAVIOUR, so subclasses would add nothing.
 */
public enum VehicleType {
    MOTORCYCLE(List.of(SpotSize.SMALL, SpotSize.MEDIUM, SpotSize.LARGE)),
    CAR(List.of(SpotSize.MEDIUM, SpotSize.LARGE)),
    TRUCK(List.of(SpotSize.LARGE));

    private final List<SpotSize> fitsIn;

    VehicleType(List<SpotSize> fitsIn) {
        this.fitsIn = fitsIn;
    }

    /** Spot sizes this vehicle can use, in order of preference (smallest first). */
    public List<SpotSize> fitsIn() {
        return fitsIn;
    }
}
