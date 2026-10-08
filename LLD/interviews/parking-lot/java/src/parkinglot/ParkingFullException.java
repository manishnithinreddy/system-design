package parkinglot;

public final class ParkingFullException extends RuntimeException {
    public ParkingFullException(VehicleType type) {
        super("No free spot for " + type);
    }
}
