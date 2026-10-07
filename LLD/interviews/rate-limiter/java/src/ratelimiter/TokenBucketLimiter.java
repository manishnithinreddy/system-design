package ratelimiter;

/**
 * Token bucket: the bucket holds up to {@code capacity} tokens and refills continuously.
 * Each request takes one token. Allows short bursts up to capacity, then a steady rate.
 *
 * Refill is LAZY: we compute how many tokens accrued since the last call instead of
 * running a background thread per bucket (which would not scale to millions of keys).
 */
public final class TokenBucketLimiter implements RateLimiter {
    private final double capacity;
    private final double tokensPerNano;
    private final TimeSource time;

    // guarded by "this"
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketLimiter(RateLimitConfig config, TimeSource time) {
        this.capacity = config.limit();
        this.tokensPerNano = (double) config.limit() / config.window().toNanos();
        this.time = time;
        this.tokens = capacity; // start full so the first burst is allowed
        this.lastRefillNanos = time.nanoTime();
    }

    @Override
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = time.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = now;
        }
    }
}
