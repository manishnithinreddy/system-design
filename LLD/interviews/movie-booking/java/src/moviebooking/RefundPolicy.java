package moviebooking;

import java.time.Duration;
import java.time.Instant;

/** Strategy: how much to refund on cancellation. Theatres and promotions differ. */
@FunctionalInterface
public interface RefundPolicy {
    long refundPaise(Booking booking, Instant cancelledAt, Instant showStartsAt);

    /** 100% if ≥ 24 h before the show, 50% if ≥ 2 h, nothing after that. */
    RefundPolicy STANDARD = (b, now, start) -> {
        Duration before = Duration.between(now, start);
        if (before.compareTo(Duration.ofHours(24)) >= 0) return b.amountPaise();
        if (before.compareTo(Duration.ofHours(2)) >= 0) return b.amountPaise() / 2;
        return 0;
    };
}
