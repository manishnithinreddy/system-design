package booking;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Facade for meeting rooms: book, cancel, hold/confirm, recurring series, availability and search. */
public final class BookingService {
    public record Found(Room room, TimeSlot slot) {}

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, RoomCalendar> calendars = new ConcurrentHashMap<>();
    private final Map<String, String> roomOfBooking = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Clock clock;

    public BookingService(Clock clock) {
        this.clock = clock;
    }

    public void addRoom(Room r) {
        rooms.put(r.id(), r);
        calendars.put(r.id(), new RoomCalendar(clock));
    }

    public Result book(String roomId, String userId, TimeSlot slot) {
        return place(roomId, List.of(newBooking(roomId, userId, slot, null)));
    }

    /** Temporary hold; becomes a real booking with confirm(), disappears by itself after ttl. */
    public Result hold(String roomId, String userId, TimeSlot slot, Duration ttl) {
        return place(roomId, List.of(newBooking(roomId, userId, slot, clock.instant().plus(ttl))));
    }

    public boolean confirm(String bookingId) {
        String roomId = roomOfBooking.get(bookingId);
        return roomId != null && calendars.get(roomId).confirm(bookingId);
    }

    public boolean cancel(String bookingId) {
        String roomId = roomOfBooking.remove(bookingId);
        return roomId != null && calendars.get(roomId).remove(bookingId);
    }

    /** Whole series or nothing. On failure, conflicts lists every occurrence that clashed. */
    public Result bookRecurring(String roomId, String userId, TimeSlot first, ZoneId zone, String rrule) {
        List<Booking> all = new ArrayList<>();
        for (TimeSlot s : Recurrence.parse(rrule).expand(first, zone)) all.add(newBooking(roomId, userId, s, null));
        return place(roomId, all);
    }

    public List<Booking> bookingsOf(String roomId, TimeSlot window) {
        return calendar(roomId).bookingsIn(window);
    }

    /** Free gaps of one room inside the window (e.g. one working day). */
    public List<TimeSlot> availability(String roomId, TimeSlot window) {
        return calendar(roomId).freeGaps(window);
    }

    /** Books the smallest room that fits. The search is a snapshot, so we try rooms in order until an atomic add wins. */
    public Optional<Booking> bookAnyRoom(String userId, int minCapacity, Set<String> features, TimeSlot slot) {
        for (Room r : matching(minCapacity, features)) {
            Result res = book(r.id(), userId, slot);
            if (res.ok()) return Optional.of(res.one());
        }
        return Optional.empty();
    }

    /** Earliest slot of the given length inside the window in ANY matching room. */
    public Optional<Found> firstFreeSlot(Duration length, TimeSlot window, int minCapacity, Set<String> features) {
        Found best = null;
        for (Room r : matching(minCapacity, features)) {
            for (TimeSlot gap : availability(r.id(), window)) {
                if (gap.length().compareTo(length) >= 0) {
                    TimeSlot s = new TimeSlot(gap.start(), gap.start().plus(length));
                    if (best == null || s.start().isBefore(best.slot().start())) best = new Found(r, s);
                    break; // gaps are in time order: first fitting gap is this room's best
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Earliest slot when ALL the listed rooms are free at once (merge their busy intervals, then look at the gaps). */
    public Optional<TimeSlot> firstCommonFreeSlot(Collection<String> roomIds, Duration length, TimeSlot window) {
        List<TimeSlot> busy = new ArrayList<>();
        for (String id : roomIds) for (Booking b : bookingsOf(id, window)) busy.add(b.slot());
        for (TimeSlot gap : Intervals.gaps(window, busy)) {
            if (gap.length().compareTo(length) >= 0) return Optional.of(new TimeSlot(gap.start(), gap.start().plus(length)));
        }
        return Optional.empty();
    }

    private List<Room> matching(int minCapacity, Set<String> features) {
        return rooms.values().stream().filter(r -> r.matches(minCapacity, features))
                .sorted(Comparator.comparingInt(Room::capacity).thenComparing(Room::id)).toList();
    }

    private Result place(String roomId, List<Booking> bs) {
        List<Conflict> clashes = calendar(roomId).tryAddAll(bs);
        if (!clashes.isEmpty()) return Result.failure(clashes);
        bs.forEach(b -> roomOfBooking.put(b.id(), roomId));
        return Result.success(bs);
    }

    private Booking newBooking(String roomId, String userId, TimeSlot slot, java.time.Instant holdUntil) {
        calendar(roomId);
        return new Booking("b" + ids.incrementAndGet(), roomId, userId, slot, holdUntil);
    }

    private RoomCalendar calendar(String roomId) {
        RoomCalendar c = calendars.get(roomId);
        if (c == null) throw new NoSuchElementException("unknown room " + roomId);
        return c;
    }
}
