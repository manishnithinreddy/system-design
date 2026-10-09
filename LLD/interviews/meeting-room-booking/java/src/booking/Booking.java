package booking;

import java.time.Instant;

/**
 * A reservation of one room for one slot. If holdUntil is not null it is only a temporary hold
 * (waiting for the user to confirm) and stops counting once the clock reaches holdUntil.
 */
public record Booking(String id, String roomId, String userId, TimeSlot slot, Instant holdUntil) {
    public boolean isHold() {
        return holdUntil != null;
    }

    public boolean expiredAt(Instant now) {
        return holdUntil != null && !now.isBefore(holdUntil);
    }

    Booking confirmed() {
        return new Booking(id, roomId, userId, slot, null);
    }
}
