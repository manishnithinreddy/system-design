package booking;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-Java test runner (no JUnit needed): each test is a named lambda, failures are counted. */
public class BookingTests {
    static final Instant DAY = Instant.parse("2030-01-07T00:00:00Z");
    static int passed = 0, failed = 0;

    static TimeSlot s(int h1, int h2) { return new TimeSlot(DAY.plusSeconds(h1 * 3600L), DAY.plusSeconds(h2 * 3600L)); }
    static TimeSlot sm(int m1, int m2) { return new TimeSlot(DAY.plusSeconds(m1 * 60L), DAY.plusSeconds(m2 * 60L)); }

    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but was " + actual);
    }
    static void yes(boolean c, String msg) { if (!c) throw new AssertionError(msg); }

    static void test(String name, Runnable r) {
        try { r.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable t) { failed++; System.out.println("FAIL " + name + " -> " + t); }
    }

    static BookingService service(Clock clock) {
        BookingService svc = new BookingService(clock);
        svc.addRoom(new Room("A", "HQ", 4, Set.of("tv")));
        svc.addRoom(new Room("B", "HQ", 8, Set.of("tv", "vc")));
        svc.addRoom(new Room("C", "HQ", 20, Set.of("tv", "vc", "stage")));
        return svc;
    }

    public static void main(String[] args) throws Exception {
        test("touching half-open intervals do not overlap", () -> {
            yes(!s(9, 10).overlaps(s(10, 11)), "[9,10) vs [10,11)");
            yes(!s(10, 11).overlaps(s(9, 10)), "reverse");
        });
        test("overlap: partial, containment, identical, one-minute", () -> {
            yes(s(9, 11).overlaps(s(10, 12)), "partial");
            yes(s(9, 12).overlaps(s(10, 11)), "containment");
            yes(s(10, 11).overlaps(s(9, 12)), "contained");
            yes(s(9, 10).overlaps(s(9, 10)), "identical");
            yes(sm(540, 601).overlaps(sm(600, 660)), "one minute shared");
        });
        test("empty or backwards slot is rejected", () -> {
            try { s(10, 10); throw new AssertionError("no exception"); } catch (IllegalArgumentException expected) {}
            try { s(11, 10); throw new AssertionError("no exception"); } catch (IllegalArgumentException expected) {}
        });
        test("book, conflict reports the blocker, touching is allowed", () -> {
            BookingService svc = service(Clock.systemUTC());
            yes(svc.book("A", "ana", s(9, 10)).ok(), "first");
            Result r = svc.book("A", "ben", sm(570, 630));
            yes(!r.ok(), "overlap must fail");
            eq("ana", r.conflicts().get(0).existing().userId());
            yes(svc.book("A", "ben", s(10, 11)).ok(), "touching after");
            yes(svc.book("A", "cy", s(8, 9)).ok(), "touching before");
            yes(svc.book("B", "ben", sm(570, 630)).ok(), "other room is independent");
        });
        test("new booking that swallows an existing one is rejected", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "ana", s(10, 11));
            yes(!svc.book("A", "ben", s(9, 12)).ok(), "contains existing");
        });
        test("cancel frees the slot", () -> {
            BookingService svc = service(Clock.systemUTC());
            Booking b = svc.book("A", "ana", s(9, 10)).one();
            yes(!svc.book("A", "ben", s(9, 10)).ok(), "taken");
            yes(svc.cancel(b.id()), "cancel");
            yes(!svc.cancel(b.id()), "second cancel is a no-op");
            yes(svc.book("A", "ben", s(9, 10)).ok(), "free again");
        });
        test("availability lists the gaps of a working day", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "x", s(10, 11));
            svc.book("A", "y", s(11, 12));
            svc.book("A", "z", s(15, 16));
            eq(List.of(s(9, 10), s(12, 15), s(16, 18)), svc.availability("A", s(9, 18)));
            eq(List.of(s(9, 18)), svc.availability("B", s(9, 18)));
        });
        test("booking that starts before the window is clipped out of the gaps", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "x", s(8, 10));
            eq(List.of(s(10, 18)), svc.availability("A", s(9, 18)));
        });
        test("find any free room by capacity and features, smallest fit first", () -> {
            BookingService svc = service(Clock.systemUTC());
            eq("A", svc.bookAnyRoom("u1", 3, Set.of(), s(9, 10)).get().roomId());
            eq("B", svc.bookAnyRoom("u2", 3, Set.of(), s(9, 10)).get().roomId());
            eq("C", svc.bookAnyRoom("u3", 3, Set.of(), s(9, 10)).get().roomId());
            yes(svc.bookAnyRoom("u4", 3, Set.of(), s(9, 10)).isEmpty(), "all full");
            eq("C", svc.bookAnyRoom("u5", 2, Set.of("stage"), s(11, 12)).get().roomId());
            yes(svc.bookAnyRoom("u6", 30, Set.of(), s(11, 12)).isEmpty(), "nobody fits 30");
        });
        test("first free slot of 90 minutes across rooms (sweep)", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "x", s(9, 11));      // A free from 11
            svc.book("B", "x", sm(540, 570));  // B free from 9:30 but...
            svc.book("B", "x", sm(600, 660));  // ...only a 30-min gap until 10:00
            svc.book("C", "x", s(9, 13));      // C free from 13
            Optional<BookingService.Found> f = svc.firstFreeSlot(Duration.ofMinutes(90), s(9, 18), 1, Set.of());
            eq("A", f.get().room().id());
            eq(sm(660, 750), f.get().slot());
            yes(svc.firstFreeSlot(Duration.ofHours(10), s(9, 18), 1, Set.of()).isEmpty(), "nothing that long");
        });
        test("common free slot for several rooms merges busy intervals", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "x", s(9, 11));
            svc.book("B", "x", s(10, 12));
            svc.book("C", "x", s(14, 15));
            eq(Optional.of(sm(720, 780)), svc.firstCommonFreeSlot(List.of("A", "B", "C"), Duration.ofHours(1), s(9, 18)));
            eq(Optional.of(s(15, 18)), svc.firstCommonFreeSlot(List.of("A", "B", "C"), Duration.ofHours(3), s(9, 18)));
        });
        test("intervals merge: overlapping, touching, nested, unsorted", () -> {
            eq(List.of(s(1, 5), s(6, 7)), Intervals.merge(List.of(s(3, 5), s(1, 2), s(2, 4), s(6, 7), s(3, 4))));
        });
        test("recurring weekly series books every occurrence", () -> {
            BookingService svc = service(Clock.systemUTC());
            Result r = svc.bookRecurring("A", "ana", s(9, 10), ZoneOffset.UTC, "FREQ=WEEKLY;COUNT=4");
            yes(r.ok(), "series");
            eq(4, r.booked().size());
            eq(DAY.plus(Duration.ofDays(21)).plusSeconds(9 * 3600), r.booked().get(3).slot().start());
        });
        test("recurring: one clashing occurrence rejects the whole series and names it", () -> {
            BookingService svc = service(Clock.systemUTC());
            svc.book("A", "dee", new TimeSlot(DAY.plus(Duration.ofDays(14)).plusSeconds(9 * 3600 + 1800), DAY.plus(Duration.ofDays(14)).plusSeconds(11 * 3600)));
            Result r = svc.bookRecurring("A", "ana", s(9, 10), ZoneOffset.UTC, "FREQ=WEEKLY;COUNT=4");
            yes(!r.ok(), "must fail");
            eq(1, r.conflicts().size());
            eq(DAY.plus(Duration.ofDays(14)).plusSeconds(9 * 3600), r.conflicts().get(0).requested().start());
            yes(svc.book("A", "zed", s(9, 10)).ok(), "nothing from the failed series was kept");
        });
        test("recurring keeps local wall-clock time across a DST change", () -> {
            ZoneId ny = ZoneId.of("America/New_York");
            TimeSlot first = new TimeSlot(ZonedDateTime.of(2030, 3, 4, 9, 0, 0, 0, ny).toInstant(), ZonedDateTime.of(2030, 3, 4, 10, 0, 0, 0, ny).toInstant());
            List<TimeSlot> occ = Recurrence.parse("FREQ=WEEKLY;COUNT=2").expand(first, ny);  // Mar 4 and Mar 11 (DST starts Mar 10)
            eq(Instant.parse("2030-03-04T14:00:00Z"), occ.get(0).start());   // EST = UTC-5
            eq(Instant.parse("2030-03-11T13:00:00Z"), occ.get(1).start());   // EDT = UTC-4
        });
        test("recurrence: INTERVAL, UNTIL, daily, and unsupported/unbounded rules", () -> {
            eq(3, Recurrence.parse("FREQ=DAILY;INTERVAL=2;UNTIL=20300111").expand(s(9, 10), ZoneOffset.UTC).size()); // Jan 7, 9, 11
            try { Recurrence.parse("FREQ=WEEKLY"); throw new AssertionError("unbounded accepted"); } catch (IllegalArgumentException ok) {}
            try { Recurrence.parse("FREQ=YEARLY;COUNT=2"); throw new AssertionError("yearly accepted"); } catch (IllegalArgumentException ok) {}
        });
        test("hold blocks others until confirmed or expired (injected clock)", () -> {
            MutableClock clock = new MutableClock(DAY);
            BookingService svc = service(clock);
            Booking h = svc.hold("A", "ana", s(9, 10), Duration.ofMinutes(5)).one();
            yes(!svc.book("A", "ben", s(9, 10)).ok(), "held");
            clock.advance(Duration.ofMinutes(4));
            yes(svc.confirm(h.id()), "confirm in time");
            clock.advance(Duration.ofHours(1));
            yes(!svc.book("A", "ben", s(9, 10)).ok(), "confirmed booking never expires");
        });
        test("expired hold frees the slot and cannot be confirmed", () -> {
            MutableClock clock = new MutableClock(DAY);
            BookingService svc = service(clock);
            Booking h = svc.hold("A", "ana", s(9, 10), Duration.ofMinutes(5)).one();
            clock.advance(Duration.ofMinutes(5));  // exactly at holdUntil: expired (half-open in time too)
            yes(!svc.confirm(h.id()), "too late");
            yes(svc.book("A", "ben", s(9, 10)).ok(), "slot is free again");
            eq(1, svc.bookingsOf("A", s(0, 24)).size());
        });
        test("concurrency: 32 threads, same slot, exactly one winner (200 rounds)", () -> {
            for (int round = 0; round < 200; round++) {
                BookingService svc = service(Clock.systemUTC());
                AtomicInteger wins = new AtomicInteger();
                runAll(32, i -> { if (svc.book("A", "u" + i, s(9, 10)).ok()) wins.incrementAndGet(); });
                eq(1, wins.get());
                eq(1, svc.bookingsOf("A", s(0, 24)).size());
            }
        });
        test("concurrency: random bookings and cancels never overlap (brute-force check)", () -> {
            BookingService svc = service(Clock.systemUTC());
            List<String> rooms = List.of("A", "B", "C");
            runAll(8, t -> {
                Random rnd = new Random(t);
                for (int i = 0; i < 400; i++) {
                    int start = rnd.nextInt(24 * 4 - 1), len = 1 + rnd.nextInt(6);
                    String room = rooms.get(rnd.nextInt(3));
                    Result r = svc.book(room, "t" + t, sm(start * 15, Math.min(24 * 60, (start + len) * 15)));
                    if (r.ok() && rnd.nextInt(4) == 0) svc.cancel(r.one().id());
                }
            });
            for (String room : rooms) {
                List<Booking> all = svc.bookingsOf(room, s(0, 24));
                for (int i = 0; i < all.size(); i++)
                    for (int j = i + 1; j < all.size(); j++)
                        yes(!all.get(i).slot().overlaps(all.get(j).slot()), "overlap in " + room + ": " + all.get(i) + " / " + all.get(j));
                yes(!all.isEmpty(), "something got booked");
            }
        });
        test("hotel: count-based availability, all nights or nothing", () -> {
            HotelInventory h = new HotelInventory(Map.of("STD", 2), 1.0, d -> 10_000L);
            LocalDate d1 = LocalDate.of(2030, 5, 1);
            yes(h.reserve("STD", d1, d1.plusDays(2), 2).isPresent(), "2 rooms x 2 nights");
            yes(h.reserve("STD", d1.plusDays(1), d1.plusDays(3), 1).isEmpty(), "night 2 is full, so the whole stay is refused");
            eq(2, h.available("STD", d1.plusDays(2)));  // the refused stay left nothing behind
            yes(h.reserve("STD", d1.plusDays(2), d1.plusDays(3), 1).isPresent(), "check-out day is free for the next guest");
        });
        test("hotel: overbooking sells up to floor(100 x 1.05) = 105", () -> {
            HotelInventory h = new HotelInventory(Map.of("DLX", 100), 1.05, d -> 12_000L);
            LocalDate n = LocalDate.of(2030, 5, 1);
            int sold = 0;
            while (h.reserve("DLX", n, n.plusDays(1), 1).isPresent()) sold++;
            eq(105, sold);
            eq(0, h.available("DLX", n));
        });
        test("hotel: price sums nightly rates; refund depends on how early you cancel", () -> {
            HotelInventory h = new HotelInventory(Map.of("STD", 5), 1.0, d -> d.getDayOfWeek() == DayOfWeek.SATURDAY ? 20_000L : 10_000L);
            LocalDate fri = LocalDate.of(2030, 5, 3);   // Fri, Sat, Sun nights
            HotelInventory.Reservation r = h.reserve("STD", fri, fri.plusDays(3), 1).get();
            eq(40_000L, r.totalCents());
            eq(30_000L, h.cancel(r.id(), fri.minusDays(1)));   // late: first night (100.00) kept
            HotelInventory.Reservation r2 = h.reserve("STD", fri, fri.plusDays(3), 1).get();
            eq(40_000L, h.cancel(r2.id(), fri.minusDays(10)));  // early: full refund
            eq(5, h.available("STD", fri));
        });
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Runs the task on n threads that all start at the same instant. */
    static void runAll(int n, java.util.function.IntConsumer task) {
        try { runAll0(n, task); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void runAll0(int n, java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int id = i;
            fs.add(pool.submit(() -> { ready.countDown(); try { go.await(); } catch (InterruptedException e) { return; } task.accept(id); }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
    }
}
