package moviebooking;

import java.time.Instant;
import java.util.List;

public record Booking(String id, String holdId, String showId, String userId, List<String> seatIds,
                      long amountPaise, String paymentRef, Instant bookedAt) {
    public Booking { seatIds = List.copyOf(seatIds); }
}
