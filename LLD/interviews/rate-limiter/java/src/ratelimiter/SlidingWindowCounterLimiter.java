package ratelimiter;

/**
 * Sliding window counter: keep counts for the current and previous fixed windows and
 * estimate the rolling count as:
 *
 *   estimate = previousCount * (fraction of previous window still inside the rolling window)
 *            + currentCount
 *
 * Two counters per key (cheap like fixed window), and smooths the boundary burst
 * (nearly as accurate as the log). Assumes requests in the previous window were evenly spread.
 */
public final class SlidingWindowCounterLimiter implements RateLimiter {
    private final long limit;
    private final long windowNanos;
    private final TimeSource time;

    // guarded by "this"
    private long currentWindow;
    private long currentCount;
    private long previousCount;

    public SlidingWindowCounterLimiter(RateLimitConfig config, TimeSource time) {
        this.limit = config.limit();
        this.windowNanos = config.window().toNanos();
        this.time = time;
        this.currentWindow = Math.floorDiv(time.nanoTime(), windowNanos);
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = time.nanoTime();
        long window = Math.floorDiv(now, windowNanos);

        if (window != currentWindow) {
            // If exactly one window passed, the current window becomes "previous".
            // If more passed, both are empty.
            previousCount = (window == currentWindow + 1) ? currentCount : 0;
            currentCount = 0;
            currentWindow = window;
        }

        long elapsedInWindow = Math.floorMod(now, windowNanos);
        double previousWeight = 1.0 - (double) elapsedInWindow / windowNanos;
        double estimate = previousCount * previousWeight + currentCount;

        if (estimate < limit) {
            currentCount++;
            return true;
        }
        return false;
    }
}
