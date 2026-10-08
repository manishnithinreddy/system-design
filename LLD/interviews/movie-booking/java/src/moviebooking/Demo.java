package moviebooking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Divya and Arjun both want the same seats for Friday night. */
public final class Demo {
    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));
        BookingService svc = new BookingService(clock, Duration.ofMinutes(10), RefundPolicy.STANDARD, CasSeatInventory::new);
        svc.addShow(new Show("S1", "Interstellar", Instant.parse("2026-10-10T18:30:00Z"), BookingTests.layout(3, 6),
                Map.of(SeatType.REGULAR, 25_000L, SeatType.PREMIUM, 35_000L, SeatType.RECLINER, 60_000L)));

        Hold divya = svc.holdSeats("S1", "divya", List.of("C3", "C4"));
        System.out.printf("10:00 Divya holds %s for ₹%d until %s%n", divya.seatIds(), divya.amountPaise() / 100, divya.expiresAt());
        try {
            svc.holdSeats("S1", "arjun", List.of("C4", "C5"));
        } catch (BookingException e) {
            System.out.println("10:01 Arjun tries C4+C5 -> " + e.getMessage() + " (C5 stays free: all-or-nothing)");
        }
        clock.advance(Duration.ofMinutes(11));
        System.out.println("10:11 Divya's payment app took too long; her hold has expired");
        Hold arjun = svc.holdSeats("S1", "arjun", List.of("C4", "C5"));
        Booking b = svc.confirmBooking(arjun.id(), "upi-arjun-1");
        System.out.println("10:12 Arjun holds and books " + b.seatIds() + " as " + b.id());
        try {
            svc.confirmBooking(divya.id(), "upi-divya-1");
        } catch (BookingException e) {
            System.out.println("10:12 Divya's late payment confirmation -> " + e.getMessage() + " (refund her payment)");
        }
        System.out.println("free recliners: " + svc.freeSeats("S1").stream().filter(s -> s.startsWith("C")).toList());
    }
}
