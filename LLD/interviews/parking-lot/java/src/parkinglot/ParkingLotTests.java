package parkinglot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class ParkingLotTests {
    private static int passed = 0;

    static final HourlyPricing PRICING = new HourlyPricing(
            Map.of(VehicleType.MOTORCYCLE, new BigDecimal("20"),
                   VehicleType.CAR, new BigDecimal("40"),
                   VehicleType.TRUCK, new BigDecimal("100")),
            Map.of(VehicleType.MOTORCYCLE, new BigDecimal("150"),
                   VehicleType.CAR, new BigDecimal("300"),
                   VehicleType.TRUCK, new BigDecimal("800")),
            Duration.ofMinutes(10));

    public static void main(String[] args) throws Exception {
        vehiclesGoToSmallestFittingSpot();
        motorcycleFallsBackToBiggerSpot();
        fullLotRejects();
        pricingRoundsUpWithGraceAndDailyCap();
        cannotUseTicketTwiceOrParkSamePlateTwice();
        unparkFreesSpotAndNotifiesBoards();
        leastCrowdedFloorSpreadsCars();
        concurrentGatesNeverShareASpot();
        System.out.println("All " + passed + " tests passed.");
    }

    static ParkingLot lot(SpotAllocationStrategy strategy, MutableClock clock, ParkingFloor... floors) {
        return new ParkingLot(List.of(floors), strategy, PRICING, clock);
    }

    static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
    }

    static void vehiclesGoToSmallestFittingSpot() {
        ParkingLot lot = lot(new NearestFirstStrategy(), clock(), new ParkingFloor(0, 1, 1, 1));
        assertEquals(SpotSize.SMALL, lot.park(new Vehicle("MC1", VehicleType.MOTORCYCLE)).spot().size(), "bike -> small");
        assertEquals(SpotSize.MEDIUM, lot.park(new Vehicle("CAR1", VehicleType.CAR)).spot().size(), "car -> medium");
        assertEquals(SpotSize.LARGE, lot.park(new Vehicle("TR1", VehicleType.TRUCK)).spot().size(), "truck -> large");
        pass("vehiclesGoToSmallestFittingSpot");
    }

    static void motorcycleFallsBackToBiggerSpot() {
        ParkingLot lot = lot(new NearestFirstStrategy(), clock(), new ParkingFloor(0, 1, 1, 0));
        lot.park(new Vehicle("MC1", VehicleType.MOTORCYCLE));
        assertEquals(SpotSize.MEDIUM, lot.park(new Vehicle("MC2", VehicleType.MOTORCYCLE)).spot().size(),
                "small full -> bike uses medium");
        pass("motorcycleFallsBackToBiggerSpot");
    }

    static void fullLotRejects() {
        ParkingLot lot = lot(new NearestFirstStrategy(), clock(), new ParkingFloor(0, 5, 1, 0));
        lot.park(new Vehicle("CAR1", VehicleType.CAR));
        assertThrows(ParkingFullException.class, () -> lot.park(new Vehicle("CAR2", VehicleType.CAR)),
                "car can't use small spots even though 5 are free");
        assertThrows(ParkingFullException.class, () -> lot.park(new Vehicle("TR1", VehicleType.TRUCK)), "no large spots");
        // a failed attempt must not leave the plate "reserved"
        lot.unpark(lot.park(new Vehicle("MC1", VehicleType.MOTORCYCLE)).id());
        pass("fullLotRejects");
    }

    static void pricingRoundsUpWithGraceAndDailyCap() {
        assertMoney("0", PRICING.feeFor(VehicleType.CAR, Duration.ofMinutes(10)), "within grace period");
        assertMoney("40", PRICING.feeFor(VehicleType.CAR, Duration.ofMinutes(11)), "11 min -> 1 hour");
        assertMoney("40", PRICING.feeFor(VehicleType.CAR, Duration.ofHours(1)), "exactly 1 hour");
        assertMoney("120", PRICING.feeFor(VehicleType.CAR, Duration.ofMinutes(130)), "2h10m -> 3 hours");
        assertMoney("300", PRICING.feeFor(VehicleType.CAR, Duration.ofHours(10)), "10h capped at daily 300");
        assertMoney("420", PRICING.feeFor(VehicleType.CAR, Duration.ofMinutes(26 * 60 + 5)), "26h05 -> 300 + 3x40");
        assertMoney("100", PRICING.feeFor(VehicleType.TRUCK, Duration.ofMinutes(50)), "truck rate");
        pass("pricingRoundsUpWithGraceAndDailyCap");
    }

    static void cannotUseTicketTwiceOrParkSamePlateTwice() {
        MutableClock clock = clock();
        ParkingLot lot = lot(new NearestFirstStrategy(), clock, new ParkingFloor(0, 0, 5, 0));
        Ticket t = lot.park(new Vehicle("KA01AB1234", VehicleType.CAR));
        assertThrows(IllegalStateException.class, () -> lot.park(new Vehicle("ka 01 ab 1234", VehicleType.CAR)),
                "same plate (normalised) can't park twice");
        clock.advance(Duration.ofMinutes(90));
        Receipt r = lot.unpark(t.id());
        assertMoney("80", r.fee(), "1h30 -> 2 hours x 40");
        assertThrows(InvalidTicketException.class, () -> lot.unpark(t.id()), "ticket can't be used twice");
        lot.park(new Vehicle("KA01AB1234", VehicleType.CAR)); // after exit, the car can come back
        pass("cannotUseTicketTwiceOrParkSamePlateTwice");
    }

    static void unparkFreesSpotAndNotifiesBoards() {
        ParkingLot lot = lot(new NearestFirstStrategy(), clock(), new ParkingFloor(2, 0, 3, 0));
        List<String> board = new ArrayList<>();
        lot.addListener((floor, size, free) -> board.add("F" + floor + " " + size + "=" + free));
        lot.addListener((floor, size, free) -> { throw new RuntimeException("broken LED board"); });

        Ticket t = lot.park(new Vehicle("CAR1", VehicleType.CAR));
        assertEquals(2, lot.freeSpots(SpotSize.MEDIUM), "one taken");
        lot.unpark(t.id());
        assertEquals(3, lot.freeSpots(SpotSize.MEDIUM), "spot returned");
        assertEquals(List.of("F2 MEDIUM=2", "F2 MEDIUM=3"), board, "board updates despite a broken listener");
        pass("unparkFreesSpotAndNotifiesBoards");
    }

    static void leastCrowdedFloorSpreadsCars() {
        ParkingLot lot = lot(new LeastCrowdedFloorStrategy(), clock(),
                new ParkingFloor(0, 0, 3, 0), new ParkingFloor(1, 0, 3, 0));
        Set<Integer> floorsUsed = new HashSet<>();
        for (int i = 0; i < 4; i++) floorsUsed.add(lot.park(new Vehicle("C" + i, VehicleType.CAR)).spot().floor());
        assertEquals(2, floorsUsed.size(), "both floors used");
        pass("leastCrowdedFloorSpreadsCars");
    }

    /** 64 "gates" try to park 2,000 cars into 500 spots at the same moment. */
    static void concurrentGatesNeverShareASpot() throws Exception {
        ParkingLot lot = lot(new NearestFirstStrategy(), clock(),
                new ParkingFloor(0, 0, 250, 0), new ParkingFloor(1, 0, 250, 0));
        ConcurrentLinkedQueue<Ticket> tickets = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
            for (int i = 0; i < 2_000; i++) {
                String plate = "CAR" + i;
                pool.submit(() -> {
                    start.await();
                    try {
                        tickets.add(lot.park(new Vehicle(plate, VehicleType.CAR)));
                    } catch (ParkingFullException expected) {
                        // lot full
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertEquals(500, tickets.size(), "exactly capacity tickets issued");
        Set<String> spots = new HashSet<>();
        for (Ticket t : tickets) spots.add(t.spot().id());
        assertEquals(500, spots.size(), "no spot given to two cars");
        assertEquals(0, lot.freeSpots(SpotSize.MEDIUM), "free count consistent");
        pass("concurrentGatesNeverShareASpot");
    }

    // --- tiny test helpers ---

    private static void assertMoney(String expected, BigDecimal actual, String what) {
        // compareTo, not equals: new BigDecimal("40").equals(new BigDecimal("40.00")) is false!
        if (new BigDecimal(expected).compareTo(actual) != 0) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertThrows(Class<? extends Throwable> type, Runnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
