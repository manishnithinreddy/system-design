package moviebooking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Facade used by the app/API layer.
 * Flow: pick seats -> holdSeats (10-minute hold) -> pay -> confirmBooking(holdId, paymentRef).
 */
public final class BookingService {
    public static final int MAX_SEATS_PER_BOOKING = 10;

    private final Clock clock;
    private final Duration holdTtl;
    private final RefundPolicy refundPolicy;
    private final Function<List<Seat>, SeatInventory> inventoryFactory;

    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, SeatInventory> inventories = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookingsByHold = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final AtomicLong ids = new AtomicLong();

    public BookingService(Clock clock, Duration holdTtl, RefundPolicy refundPolicy,
                          Function<List<Seat>, SeatInventory> inventoryFactory) {
        this.clock = clock;
        this.holdTtl = holdTtl;
        this.refundPolicy = refundPolicy;
        this.inventoryFactory = inventoryFactory;
    }

    public void addShow(Show show) {
        if (shows.putIfAbsent(show.id(), show) != null) throw new BookingException("show exists: " + show.id());
        inventories.put(show.id(), inventoryFactory.apply(show.seats()));
    }

    public List<String> freeSeats(String showId) {
        return inventory(showId).freeSeats(clock.instant());
    }

    public Hold holdSeats(String showId, String userId, List<String> seatIds) {
        Show show = show(showId);
        Instant now = clock.instant();
        if (!now.isBefore(show.startsAt())) throw new BookingException("show already started");
        if (seatIds.isEmpty() || seatIds.size() > MAX_SEATS_PER_BOOKING) {
            throw new BookingException("choose 1-" + MAX_SEATS_PER_BOOKING + " seats");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) throw new BookingException("duplicate seat");
        Map<String, Seat> layout = new java.util.HashMap<>();
        show.seats().forEach(s -> layout.put(s.id(), s));
        long amount = 0;
        for (String id : seatIds) {
            Seat seat = layout.get(id);
            if (seat == null) throw new BookingException("no seat " + id);
            amount += show.pricePaise().get(seat.type());
        }

        String holdId = "H" + ids.incrementAndGet();
        Instant expiresAt = now.plus(holdTtl);
        if (!inventory(showId).tryHold(seatIds, holdId, now, expiresAt)) {
            throw new BookingException("some of those seats were just taken");
        }
        Hold hold = new Hold(holdId, showId, userId, seatIds, expiresAt, amount);
        holds.put(holdId, hold);
        return hold;
    }

    /** Idempotent: confirming the same hold again returns the same booking. */
    public Booking confirmBooking(String holdId, String paymentRef) {
        Hold hold = holds.get(holdId);
        if (hold == null) throw new BookingException("unknown hold " + holdId);
        // compute() runs atomically per key, so two concurrent confirms of one hold can't both book.
        return bookingsByHold.compute(holdId, (k, existing) -> {
            if (existing != null) return existing;                       // retry: same answer
            String bookingId = "B" + ids.incrementAndGet();
            Instant now = clock.instant();
            if (!inventory(hold.showId()).confirm(hold.seatIds(), holdId, bookingId, now)) {
                throw new BookingException("hold expired or released; please choose seats again");
            }
            Booking b = new Booking(bookingId, holdId, hold.showId(), hold.userId(), hold.seatIds(),
                    hold.amountPaise(), paymentRef, now);
            bookings.put(bookingId, b);
            return b;
        });
    }

    public void releaseHold(String holdId) {
        Hold hold = holds.get(holdId);
        if (hold != null) inventory(hold.showId()).release(hold.seatIds(), holdId);
    }

    /** @return refund amount in paise */
    public long cancelBooking(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new BookingException("unknown booking " + bookingId);
        if (!cancelled.add(bookingId)) throw new BookingException("already cancelled");
        Instant now = clock.instant();
        Show show = show(b.showId());
        if (!now.isBefore(show.startsAt())) {
            cancelled.remove(bookingId);
            throw new BookingException("show already started");
        }
        inventory(b.showId()).cancel(b.seatIds(), bookingId);
        return refundPolicy.refundPaise(b, now, show.startsAt());
    }

    private Show show(String id) {
        Show s = shows.get(id);
        if (s == null) throw new BookingException("unknown show " + id);
        return s;
    }

    private SeatInventory inventory(String showId) {
        show(showId);
        return inventories.get(showId);
    }
}
