package booking;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * All bookings of ONE room, kept in a TreeMap keyed by start. Because bookings of a room never overlap,
 * a new slot can only clash with the booking at or just before its start, or the one just after it:
 * two O(log n) lookups instead of scanning everything. One lock per room: the check and the insert
 * happen under the same lock, so two people can never both pass the check.
 */
final class RoomCalendar {
    private final TreeMap<Instant, Booking> byStart = new TreeMap<>();
    private final Map<String, Booking> byId = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;

    RoomCalendar(Clock clock) {
        this.clock = clock;
    }

    /** Adds the booking unless something live overlaps it. Returns the blocking booking, or empty on success. */
    Optional<Booking> tryAdd(Booking b) {
        lock.lock();
        try {
            Booking c = findConflict(b.slot(), clock.instant());
            if (c != null) return Optional.of(c);
            put(b);
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /** All-or-nothing: returns every clash (requested slot -> existing booking); inserts only if there are none. */
    List<Conflict> tryAddAll(List<Booking> bs) {
        lock.lock();
        try {
            Instant now = clock.instant();
            List<Conflict> clashes = new ArrayList<>();
            for (Booking b : bs) {
                Booking c = findConflict(b.slot(), now);
                if (c != null) clashes.add(new Conflict(b.slot(), c));
            }
            if (clashes.isEmpty()) bs.forEach(this::put);
            return clashes;
        } finally {
            lock.unlock();
        }
    }

    boolean remove(String bookingId) {
        lock.lock();
        try {
            Booking b = byId.remove(bookingId);
            if (b == null) return false;
            byStart.remove(b.slot().start());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Turns a live hold into a booking. False if unknown, already expired, or not a hold. */
    boolean confirm(String bookingId) {
        lock.lock();
        try {
            Booking b = byId.get(bookingId);
            if (b == null || !b.isHold() || b.expiredAt(clock.instant())) return false;
            put(b.confirmed());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Live bookings that overlap the window, in time order. */
    List<Booking> bookingsIn(TimeSlot window) {
        lock.lock();
        try {
            Instant now = clock.instant();
            Instant from = Optional.ofNullable(byStart.floorKey(window.start())).orElse(window.start());
            List<Booking> out = new ArrayList<>();
            for (Booking b : byStart.subMap(from, true, window.end(), false).values()) {
                if (!b.expiredAt(now) && b.slot().overlaps(window)) out.add(b);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    List<TimeSlot> freeGaps(TimeSlot window) {
        List<TimeSlot> busy = new ArrayList<>();
        for (Booking b : bookingsIn(window)) busy.add(b.slot());
        return Intervals.gaps(window, busy);
    }

    private void put(Booking b) {
        Booking old = byId.put(b.id(), b);
        if (old != null) byStart.remove(old.slot().start());
        byStart.put(b.slot().start(), b);
    }

    /** The two-neighbour check. Expired holds met on the way are deleted (lazy cleanup). */
    private Booking findConflict(TimeSlot s, Instant now) {
        while (true) {
            Map.Entry<Instant, Booking> before = byStart.floorEntry(s.start());          // starts at or before s.start
            if (before != null && before.getValue().slot().end().isAfter(s.start())) {   // and still running
                if (dropIfExpired(before.getValue(), now)) continue;
                return before.getValue();
            }
            Map.Entry<Instant, Booking> after = byStart.higherEntry(s.start());          // first one starting later
            if (after != null && after.getValue().slot().start().isBefore(s.end())) {    // starts before s ends
                if (dropIfExpired(after.getValue(), now)) continue;
                return after.getValue();
            }
            return null;
        }
    }

    private boolean dropIfExpired(Booking b, Instant now) {
        if (!b.expiredAt(now)) return false;
        byStart.remove(b.slot().start());
        byId.remove(b.id());
        return true;
    }
}
