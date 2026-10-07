package ratelimiter;

/**
 * Rate limiter for ONE client (one bucket / one window).
 * Implementations must be thread-safe: many request threads call tryAcquire concurrently.
 */
public interface RateLimiter {
    /** @return true if the request is allowed (and consumes one permit), false if it must be rejected. */
    boolean tryAcquire();
}
