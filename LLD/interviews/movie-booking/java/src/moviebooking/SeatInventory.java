package moviebooking;

import java.time.Instant;
import java.util.List;

/**
 * Seat states for ONE show. Every multi-seat operation is ALL-OR-NOTHING:
 * either every seat changes, or none does. Implementations must be thread-safe.
 */
public interface SeatInventory {
    /** Hold all seats until {@code expiresAt}, or none if any is not free. */
    boolean tryHold(List<String> seatIds, String holdId, Instant now, Instant expiresAt);

    /** Turn this hold's seats into a booking, only if the hold is still valid for ALL of them. */
    boolean confirm(List<String> seatIds, String holdId, String bookingId, Instant now);

    /** Give back seats held by this hold (no-op for seats no longer held by it). */
    void release(List<String> seatIds, String holdId);

    /** Free seats of a cancelled booking. */
    void cancel(List<String> seatIds, String bookingId);

    List<String> freeSeats(Instant now);
}
