package moviebooking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Plain-Java tests (no JUnit). Every behaviour test runs against BOTH inventory implementations. */
public final class BookingTests {
    private static int passed = 0;
    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    static final Instant SHOW_TIME = Instant.parse("2026-10-10T18:30:00Z");

    public static void main(String[] args) throws Exception {
        for (var impl : List.<Map.Entry<String, Function<List<Seat>, SeatInventory>>>of(
                Map.entry("Locking", LockingSeatInventory::new),
                Map.entry("CAS", CasSeatInventory::new))) {
            String n = impl.getKey();
            var f = impl.getValue();
            holdThenConfirm(n, f);
            seatCannotBeHeldTwice(n, f);
            holdIsAllOrNothing(n, f);
            expiredHoldFreesSeatsAndCannotBeConfirmed(n, f);
            confirmIsIdempotent(n, f);
            releaseAndCancelFreeSeats(n, f);
            concurrentHoldsNeverOverlap(n, f);
            everyoneWantsTheSameSeats(n, f);
        }
        validationAndRefundPolicy();
        System.out.println("All " + passed + " tests passed.");
    }

    static List<Seat> layout(int rows, int perRow) {
        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 1; c <= perRow; c++) {
                char row = (char) ('A' + r);
                seats.add(new Seat("" + row + c, r < rows - 1 ? SeatType.REGULAR : SeatType.RECLINER));
            }
        }
        return seats;
    }

    static BookingService service(MutableClock clock, Function<List<Seat>, SeatInventory> f, int rows, int perRow) {
        BookingService svc = new BookingService(clock, Duration.ofMinutes(10), RefundPolicy.STANDARD, f);
        svc.addShow(new Show("S1", "Interstellar", SHOW_TIME, layout(rows, perRow),
                Map.of(SeatType.REGULAR, 25_000L, SeatType.PREMIUM, 35_000L, SeatType.RECLINER, 60_000L)));
        return svc;
    }

    static void holdThenConfirm(String n, Function<List<Seat>, SeatInventory> f) {
        BookingService svc = service(new MutableClock(NOW), f, 3, 5);
        Hold h = svc.holdSeats("S1", "divya", List.of("A1", "C2"));
        assertEquals(85_000L, h.amountPaise(), "₹250 regular + ₹600 recliner");
        assertTrue(!svc.freeSeats("S1").contains("A1"), "held seat not free");
        Booking b = svc.confirmBooking(h.id(), "upi-123");
        assertEquals(List.of("A1", "C2"), b.seatIds(), "booked seats");
        assertEquals(13, svc.freeSeats("S1").size(), "15 - 2 booked");
        pass(n + ": holdThenConfirm");
    }

    static void seatCannotBeHeldTwice(String n, Function<List<Seat>, SeatInventory> f) {
        BookingService svc = service(new MutableClock(NOW), f, 3, 5);
        svc.holdSeats("S1", "divya", List.of("B3"));
        assertThrows(() -> svc.holdSeats("S1", "arjun", List.of("B3")), "second hold fails");
        pass(n + ": seatCannotBeHeldTwice");
    }

    static void holdIsAllOrNothing(String n, Function<List<Seat>, SeatInventory> f) {
        BookingService svc = service(new MutableClock(NOW), f, 3, 5);
        svc.holdSeats("S1", "divya", List.of("B3"));
        assertThrows(() -> svc.holdSeats("S1", "arjun", List.of("B1", "B2", "B3", "B4")), "one taken -> none held");
        assertTrue(svc.freeSeats("S1").containsAll(List.of("B1", "B2", "B4")), "B1, B2, B4 were rolled back");
        pass(n + ": holdIsAllOrNothing");
    }

    static void expiredHoldFreesSeatsAndCannotBeConfirmed(String n, Function<List<Seat>, SeatInventory> f) {
        MutableClock clock = new MutableClock(NOW);
        BookingService svc = service(clock, f, 3, 5);
        Hold slow = svc.holdSeats("S1", "divya", List.of("A1", "A2"));
        clock.advance(Duration.ofMinutes(10));                       // hold expires exactly now
        assertTrue(svc.freeSeats("S1").contains("A1"), "expired hold counts as free");
        svc.holdSeats("S1", "arjun", List.of("A2"));                 // someone else takes one seat
        assertThrows(() -> svc.confirmBooking(slow.id(), "upi-late"), "late confirm fails");
        assertTrue(svc.freeSeats("S1").contains("A1"), "A1 still free after failed confirm");
        pass(n + ": expiredHoldFreesSeatsAndCannotBeConfirmed");
    }

    static void confirmIsIdempotent(String n, Function<List<Seat>, SeatInventory> f) {
        BookingService svc = service(new MutableClock(NOW), f, 3, 5);
        Hold h = svc.holdSeats("S1", "divya", List.of("A1"));
        Booking first = svc.confirmBooking(h.id(), "upi-1");
        Booking again = svc.confirmBooking(h.id(), "upi-1");
        assertEquals(first, again, "payment callback retried -> same booking");
        pass(n + ": confirmIsIdempotent");
    }

    static void releaseAndCancelFreeSeats(String n, Function<List<Seat>, SeatInventory> f) {
        MutableClock clock = new MutableClock(NOW);
        BookingService svc = service(clock, f, 3, 5);
        Hold h = svc.holdSeats("S1", "divya", List.of("A1"));
        svc.releaseHold(h.id());
        assertTrue(svc.freeSeats("S1").contains("A1"), "released");
        Hold h2 = svc.holdSeats("S1", "divya", List.of("C1"));
        Booking b = svc.confirmBooking(h2.id(), "upi-2");
        long refund = svc.cancelBooking(b.id());                     // > 24 h before the show
        assertEquals(60_000L, refund, "full refund");
        assertTrue(svc.freeSeats("S1").contains("C1"), "cancelled seat free again");
        assertThrows(() -> svc.cancelBooking(b.id()), "can't cancel twice");
        pass(n + ": releaseAndCancelFreeSeats");
    }

    /** 64 threads grab random groups of 1-4 seats out of 40. No seat may end up in two successful holds. */
    static void concurrentHoldsNeverOverlap(String n, Function<List<Seat>, SeatInventory> f) throws Exception {
        BookingService svc = service(new MutableClock(NOW), f, 4, 10);
        List<String> all = layout(4, 10).stream().map(Seat::id).toList();
        ConcurrentLinkedQueue<Hold> wins = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
            for (int t = 0; t < 64; t++) {
                int seed = t;
                pool.submit(() -> {
                    Random rnd = new Random(seed);
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        List<String> pick = new ArrayList<>(all);
                        Collections.shuffle(pick, rnd);
                        try {
                            wins.add(svc.holdSeats("S1", "u" + seed, pick.subList(0, 1 + rnd.nextInt(4))));
                        } catch (BookingException taken) { /* expected under contention */ }
                    }
                    return null;
                });
            }
            start.countDown();
        }
        Set<String> seen = new HashSet<>();
        for (Hold h : wins) {
            for (String s : h.seatIds()) {
                if (!seen.add(s)) throw new AssertionError(n + ": seat " + s + " held twice!");
            }
        }
        assertEquals(40 - seen.size(), svc.freeSeats("S1").size(), "free seats = 40 - held seats");
        pass(n + ": concurrentHoldsNeverOverlap (" + wins.size() + " holds, " + seen.size() + " seats)");
    }

    /** Blockbuster opening: 200 users all want the same 2 middle seats at once. Exactly one wins. */
    static void everyoneWantsTheSameSeats(String n, Function<List<Seat>, SeatInventory> f) throws Exception {
        BookingService svc = service(new MutableClock(NOW), f, 3, 10);
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(200)) {
            for (int t = 0; t < 200; t++) {
                int user = t;
                pool.submit(() -> {
                    start.await();
                    try {
                        svc.holdSeats("S1", "u" + user, List.of("B5", "B6"));
                        winners.incrementAndGet();
                    } catch (BookingException ignored) { }
                    return null;
                });
            }
            start.countDown();
        }
        assertEquals(1, winners.get(), "exactly one winner");
        pass(n + ": everyoneWantsTheSameSeats");
    }

    static void validationAndRefundPolicy() {
        MutableClock clock = new MutableClock(NOW);
        BookingService svc = service(clock, LockingSeatInventory::new, 3, 5);
        assertThrows(() -> svc.holdSeats("S1", "x", List.of()), "no seats");
        assertThrows(() -> svc.holdSeats("S1", "x", List.of("A1", "A1")), "duplicate");
        assertThrows(() -> svc.holdSeats("S1", "x", List.of("Z9")), "unknown seat");
        assertThrows(() -> svc.holdSeats("S1", "x", layout(3, 5).stream().map(Seat::id).limit(11).toList()), "max 10");
        Hold h = svc.holdSeats("S1", "divya", List.of("A1", "A2"));
        Booking b = svc.confirmBooking(h.id(), "upi");
        clock.advance(Duration.between(NOW, SHOW_TIME).minusHours(3));   // 3 h before show
        assertEquals(25_000L, svc.cancelBooking(b.id()), "50% refund between 2 h and 24 h");
        clock.advance(Duration.ofHours(4));                               // show started
        assertThrows(() -> svc.holdSeats("S1", "late", List.of("B1")), "can't book a started show");
        pass("validationAndRefundPolicy");
    }

    // --- helpers ---

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    private static void assertThrows(Runnable r, String what) {
        try { r.run(); } catch (BookingException expected) { return; }
        throw new AssertionError(what + ": expected BookingException");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
