package ratelimiter;

/** Factory: callers ask for "a limiter for this config" and never name a concrete class. */
public final class RateLimiterFactory {
    private final TimeSource time;

    public RateLimiterFactory(TimeSource time) {
        this.time = time;
    }

    public RateLimiter create(RateLimitConfig config) {
        return switch (config.algorithm()) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(config, time);
            case TOKEN_BUCKET_LOCK_FREE -> new LockFreeTokenBucketLimiter(config, time);
            case FIXED_WINDOW -> new FixedWindowLimiter(config, time);
            case SLIDING_WINDOW_LOG -> new SlidingWindowLogLimiter(config, time);
            case SLIDING_WINDOW_COUNTER -> new SlidingWindowCounterLimiter(config, time);
        };
    }
}
