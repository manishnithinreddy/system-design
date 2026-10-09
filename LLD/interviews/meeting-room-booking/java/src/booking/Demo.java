package booking;

import java.time.*;
import java.util.Set;

public class Demo {
    static final Instant DAY = Instant.parse("2030-01-07T00:00:00Z");

    static TimeSlot slot(int h1, int m1, int h2, int m2) {
        return new TimeSlot(DAY.plusSeconds(h1 * 3600L + m1 * 60L), DAY.plusSeconds(h2 * 3600L + m2 * 60L));
    }

    static String fmt(TimeSlot s) {
        return s.start().toString().substring(11, 16) + "-" + s.end().toString().substring(11, 16);
    }

    public static void main(String[] a) {
        MutableClock clock = new MutableClock(DAY);
        BookingService svc = new BookingService(clock);
        svc.addRoom(new Room("Aspen", "HQ", 4, Set.of("tv")));
        svc.addRoom(new Room("Birch", "HQ", 10, Set.of("tv", "vc")));
        TimeSlot workday = slot(9, 0, 18, 0);

        System.out.println("> Ana books Aspen 09:00-10:00, Ben tries 09:30-10:30 and then 10:00-11:00");
        System.out.println("Ana: " + svc.book("Aspen", "ana", slot(9, 0, 10, 0)).ok());
        Result r = svc.book("Aspen", "ben", slot(9, 30, 10, 30));
        System.out.println("Ben (overlap): " + r.ok() + ", blocked by " + r.conflicts().get(0).existing().userId() + " " + fmt(r.conflicts().get(0).existing().slot()));
        System.out.println("Ben (touching): " + svc.book("Aspen", "ben", slot(10, 0, 11, 0)).ok());

        System.out.println("> free gaps in Aspen: " + svc.availability("Aspen", workday).stream().map(Demo::fmt).toList());
        System.out.println("> a room for 6 people with video-conferencing at 09:00-10:00: "
                + svc.bookAnyRoom("cy", 6, Set.of("vc"), slot(9, 0, 10, 0)).map(Booking::roomId).orElse("none"));
        System.out.println("> first free 90 min for 3+ people: "
                + svc.firstFreeSlot(Duration.ofMinutes(90), workday, 3, Set.of()).map(f -> f.room().id() + " " + fmt(f.slot())).orElse("none"));

        System.out.println("> weekly standup x3 at Aspen, Mondays 11:00-11:30, but 2030-01-21 is taken");
        TimeSlot jan21 = new TimeSlot(Instant.parse("2030-01-21T11:00:00Z"), Instant.parse("2030-01-21T12:00:00Z"));
        svc.book("Aspen", "dee", jan21);
        Result series = svc.bookRecurring("Aspen", "eve", new TimeSlot(DAY.plusSeconds(11 * 3600), DAY.plusSeconds(11 * 3600 + 1800)),
                ZoneOffset.UTC, "FREQ=WEEKLY;COUNT=3");
        System.out.println("series ok: " + series.ok() + ", clashing occurrences: "
                + series.conflicts().stream().map(c -> c.requested().start().toString()).toList());

        System.out.println("> hold for 5 minutes");
        Result h = svc.hold("Birch", "fay", slot(14, 0, 15, 0), Duration.ofMinutes(5));
        System.out.println("other user while held: " + svc.book("Birch", "gus", slot(14, 0, 15, 0)).ok());
        clock.advance(Duration.ofMinutes(6));
        System.out.println("confirm after 6 min: " + svc.confirm(h.one().id()) + ", other user now: " + svc.book("Birch", "gus", slot(14, 0, 15, 0)).ok());

        HotelInventory hotel = new HotelInventory(java.util.Map.of("DELUXE", 100), 1.05, d -> 12_000L);
        int sold = 0;
        while (hotel.reserve("DELUXE", LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 2), 1).isPresent()) sold++;
        System.out.println("> hotel: 100 deluxe rooms, 105% allowed, sold " + sold + " for one night");
    }
}
