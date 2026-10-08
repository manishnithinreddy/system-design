package moviebooking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optimistic, lock-free: each seat is its own AtomicReference<SeatState>.
 * To hold several seats: claim them one by one with compare-and-set (CAS); if any claim fails,
 * UNDO the ones already claimed and report failure. No thread ever waits for another, so deadlock
 * is impossible, and users booking different seats of the same show never block each other.
 */
public final class CasSeatInventory implements SeatInventory {
    private final Map<String, AtomicReference<SeatState>> seats;   // never modified after construction

    public CasSeatInventory(List<Seat> layout) {
        Map<String, AtomicReference<SeatState>> m = new TreeMap<>();
        for (Seat s : layout) m.put(s.id(), new AtomicReference<>(SeatState.Available.INSTANCE));
        this.seats = Map.copyOf(m);
    }

    @Override
    public boolean tryHold(List<String> ids, String holdId, Instant now, Instant expiresAt) {
        SeatState.Held mine = new SeatState.Held(holdId, expiresAt);
        List<String> claimed = new ArrayList<>();
        for (String id : ids.stream().sorted().toList()) {     // fixed order: fewer "half-claimed" collisions
            AtomicReference<SeatState> ref = seats.get(id);
            SeatState current = ref.get();
            if (!SeatState.isFree(current, now) || !ref.compareAndSet(current, mine)) {
                for (String c : claimed) seats.get(c).compareAndSet(mine, SeatState.Available.INSTANCE); // roll back
                return false;
            }
            claimed.add(id);
        }
        return true;
    }

    @Override
    public boolean confirm(List<String> ids, String holdId, String bookingId, Instant now) {
        List<SeatState> expected = new ArrayList<>();
        for (String id : ids) {                                  // phase 1: is the hold still valid everywhere?
            SeatState s = seats.get(id).get();
            if (!(s instanceof SeatState.Held h && h.holdId().equals(holdId) && now.isBefore(h.expiresAt()))) return false;
            expected.add(s);
        }
        SeatState.Booked booked = new SeatState.Booked(bookingId);
        for (int i = 0; i < ids.size(); i++) {                  // phase 2: swap each Held -> Booked
            if (!seats.get(ids.get(i)).compareAndSet(expected.get(i), booked)) {
                // someone took a seat in between (only possible if our hold had expired): undo
                for (int j = 0; j < i; j++) seats.get(ids.get(j)).compareAndSet(booked, expected.get(j));
                return false;
            }
        }
        return true;
    }

    @Override
    public void release(List<String> ids, String holdId) {
        for (String id : ids) {
            AtomicReference<SeatState> ref = seats.get(id);
            SeatState s = ref.get();
            if (s instanceof SeatState.Held h && h.holdId().equals(holdId)) ref.compareAndSet(s, SeatState.Available.INSTANCE);
        }
    }

    @Override
    public void cancel(List<String> ids, String bookingId) {
        for (String id : ids) {
            AtomicReference<SeatState> ref = seats.get(id);
            SeatState s = ref.get();
            if (s instanceof SeatState.Booked b && b.bookingId().equals(bookingId)) ref.compareAndSet(s, SeatState.Available.INSTANCE);
        }
    }

    @Override
    public List<String> freeSeats(Instant now) {
        return seats.entrySet().stream().filter(e -> SeatState.isFree(e.getValue().get(), now))
                .map(Map.Entry::getKey).sorted().toList();
    }
}
