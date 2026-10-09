package booking;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/**
 * Hotel variant: guests buy a ROOM TYPE for a range of nights, not a specific room. Availability is a
 * counter per (type, night). Check-in is inclusive, check-out exclusive: nights = [checkIn, checkOut).
 * overbookFactor 1.05 means we sell up to 105% of the physical rooms, betting on no-shows.
 */
public final class HotelInventory {
    public record Reservation(String id, String type, LocalDate checkIn, LocalDate checkOut, int rooms, long totalCents) {}

    private final Map<String, Integer> physical;
    private final double overbookFactor;
    private final Function<LocalDate, Long> nightlyRateCents;
    private final Map<String, Map<LocalDate, Integer>> sold = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private int nextId = 1;

    public HotelInventory(Map<String, Integer> physical, double overbookFactor, Function<LocalDate, Long> nightlyRateCents) {
        this.physical = Map.copyOf(physical);
        this.overbookFactor = overbookFactor;
        this.nightlyRateCents = nightlyRateCents;
    }

    public int sellableLimit(String type) {
        return (int) Math.floor(physical.get(type) * overbookFactor);
    }

    public synchronized int available(String type, LocalDate night) {
        return sellableLimit(type) - sold.computeIfAbsent(type, t -> new HashMap<>()).getOrDefault(night, 0);
    }

    /** All nights must have room, otherwise nothing is reserved. */
    public synchronized Optional<Reservation> reserve(String type, LocalDate checkIn, LocalDate checkOut, int rooms) {
        if (!checkIn.isBefore(checkOut) || rooms < 1) throw new IllegalArgumentException("bad stay");
        long total = 0;
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) {
            if (available(type, d) < rooms) return Optional.empty();
            total += nightlyRateCents.apply(d) * rooms;
        }
        Map<LocalDate, Integer> m = sold.get(type);
        for (LocalDate d = checkIn; d.isBefore(checkOut); d = d.plusDays(1)) m.merge(d, rooms, Integer::sum);
        Reservation r = new Reservation("r" + nextId++, type, checkIn, checkOut, rooms, total);
        reservations.put(r.id(), r);
        return Optional.of(r);
    }

    /** Frees the nights and returns the refund in cents: free up to 2 days before check-in, otherwise the first night is kept. */
    public synchronized long cancel(String reservationId, LocalDate today) {
        Reservation r = reservations.remove(reservationId);
        if (r == null) throw new NoSuchElementException(reservationId);
        Map<LocalDate, Integer> m = sold.get(r.type());
        for (LocalDate d = r.checkIn(); d.isBefore(r.checkOut()); d = d.plusDays(1)) m.merge(d, -r.rooms(), Integer::sum);
        boolean free = ChronoUnit.DAYS.between(today, r.checkIn()) >= 2;
        return free ? r.totalCents() : r.totalCents() - nightlyRateCents.apply(r.checkIn()) * r.rooms();
    }
}
