package moviebooking;

import java.time.Instant;
import java.util.List;

public record Hold(String id, String showId, String userId, List<String> seatIds, Instant expiresAt, long amountPaise) {
    public Hold { seatIds = List.copyOf(seatIds); }
}
