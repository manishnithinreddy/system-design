package booking;

import java.util.List;

/** Outcome of a booking attempt: either everything was booked, or nothing was and conflicts say why. */
public record Result(List<Booking> booked, List<Conflict> conflicts) {
    public boolean ok() {
        return conflicts.isEmpty();
    }

    public Booking one() {
        return booked.get(0);
    }

    static Result success(List<Booking> b) {
        return new Result(b, List.of());
    }

    static Result failure(List<Conflict> c) {
        return new Result(List.of(), c);
    }
}
