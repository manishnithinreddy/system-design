package parkinglot;

import java.util.Objects;

/** A value object: two vehicles with the same plate and type are the same vehicle. */
public record Vehicle(String licensePlate, VehicleType type) {
    public Vehicle {
        Objects.requireNonNull(type, "type");
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("licensePlate is required");
        }
        licensePlate = licensePlate.replace(" ", "").toUpperCase(); // "ka 01 ab 1234" == "KA01AB1234"
    }
}
