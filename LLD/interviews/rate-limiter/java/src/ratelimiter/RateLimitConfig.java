package ratelimiter;

import java.time.Duration;
import java.util.Objects;

/**
 * "Allow {@code limit} requests per {@code window}" using {@code algorithm}.
 * For token bucket: capacity = limit, refill rate = limit / window.
 */
public record RateLimitConfig(Algorithm algorithm, long limit, Duration window) {
    public RateLimitConfig {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(window, "window");
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be > 0");
    }

    public static RateLimitConfig of(Algorithm algorithm, long limit, Duration window) {
        return new RateLimitConfig(algorithm, limit, window);
    }
}
