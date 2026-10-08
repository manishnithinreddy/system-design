package moviebooking;

import java.time.Instant;

/** The state of ONE seat for ONE show. Immutable: changing state = replacing the object. */
public sealed interface SeatState permits SeatState.Available, SeatState.Held, SeatState.Booked {

    record Available() implements SeatState {
        public static final Available INSTANCE = new Available();
    }

    /** Temporarily reserved while the user pays. Counts as AVAILABLE once expiresAt has passed. */
    record Held(String holdId, Instant expiresAt) implements SeatState {}

    record Booked(String bookingId) implements SeatState {}

    /** Can someone new take this seat right now? */
    static boolean isFree(SeatState s, Instant now) {
        return s instanceof Available || (s instanceof Held h && !now.isBefore(h.expiresAt()));
    }
}
