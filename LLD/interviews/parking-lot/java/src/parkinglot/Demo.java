package parkinglot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A morning at a two-floor mall parking lot. */
public final class Demo {
    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
        ParkingLot lot = new ParkingLot(
                List.of(new ParkingFloor(0, 2, 3, 1), new ParkingFloor(1, 2, 3, 1)),
                new NearestFirstStrategy(), ParkingLotTests.PRICING, clock);
        lot.addListener((floor, size, free) ->
                System.out.printf("   [LED board] Floor %d: %d %s spots free%n", floor, free, size));

        Ticket car = lot.park(new Vehicle("KA01AB1234", VehicleType.CAR));
        System.out.println("09:00 car  enters -> spot " + car.spot().id());

        clock.advance(Duration.ofMinutes(130));
        Receipt r = lot.unpark(car.id());
        System.out.println("11:10 car  exits after " + r.parkedFor().toMinutes() + " min -> pays Rs " + r.fee()
                + " (rounded up to 3 hours x Rs 40)");

        Ticket bike = lot.park(new Vehicle("KA05XY9876", VehicleType.MOTORCYCLE));
        System.out.println("11:10 bike enters -> spot " + bike.spot().id());
        clock.advance(Duration.ofMinutes(5));
        Receipt rb = lot.unpark(bike.id());
        System.out.println("11:15 bike exits after " + rb.parkedFor().toMinutes() + " min -> pays Rs " + rb.fee()
                + " (within 10-min grace period)");
    }
}
