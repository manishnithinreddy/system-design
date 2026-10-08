package moviebooking;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pessimistic: one lock per show. Every operation runs alone, so "check all seats, then change all
 * seats" is trivially atomic. Simple and correct; a show has a few hundred seats and bookings take
 * microseconds, so contention is rarely a problem except at blockbuster launches.
 */
public final class LockingSeatInventory implements SeatInventory {
    private final Map<String, SeatState> seats = new HashMap<>();

    public LockingSeatInventory(List<Seat> layout) {
        for (Seat s : layout) seats.put(s.id(), SeatState.Available.INSTANCE);
    }

    @Override
    public synchronized boolean tryHold(List<String> ids, String holdId, Instant now, Instant expiresAt) {
        for (String id : ids) {
            if (!SeatState.isFree(seats.get(id), now)) return false;   // check ALL first...
        }
        for (String id : ids) seats.put(id, new SeatState.Held(holdId, expiresAt));  // ...then change all
        return true;
    }

    @Override
    public synchronized boolean confirm(List<String> ids, String holdId, String bookingId, Instant now) {
        for (String id : ids) {
            if (!(seats.get(id) instanceof SeatState.Held h && h.holdId().equals(holdId) && now.isBefore(h.expiresAt()))) {
                return false;
            }
        }
        for (String id : ids) seats.put(id, new SeatState.Booked(bookingId));
        return true;
    }

    @Override
    public synchronized void release(List<String> ids, String holdId) {
        for (String id : ids) {
            if (seats.get(id) instanceof SeatState.Held h && h.holdId().equals(holdId)) {
                seats.put(id, SeatState.Available.INSTANCE);
            }
        }
    }

    @Override
    public synchronized void cancel(List<String> ids, String bookingId) {
        for (String id : ids) {
            if (seats.get(id) instanceof SeatState.Booked b && b.bookingId().equals(bookingId)) {
                seats.put(id, SeatState.Available.INSTANCE);
            }
        }
    }

    @Override
    public synchronized List<String> freeSeats(Instant now) {
        return seats.entrySet().stream().filter(e -> SeatState.isFree(e.getValue(), now))
                .map(Map.Entry::getKey).sorted().toList();
    }
}
