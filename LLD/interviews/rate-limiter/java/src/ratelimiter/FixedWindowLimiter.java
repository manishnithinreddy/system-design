package ratelimiter;

/**
 * Fixed window counter: count requests in the current window (e.g. this minute); reset at the boundary.
 * Cheapest algorithm (one counter), but allows up to 2x the limit around a boundary:
 * 100 requests at 12:00:59 and 100 more at 12:01:00.
 */
public final class FixedWindowLimiter implements RateLimiter {
    private final long limit;
    private final long windowNanos;
    private final TimeSource time;

    // guarded by "this"
    private long currentWindow;
    private long count;

    public FixedWindowLimiter(RateLimitConfig config, TimeSource time) {
        this.limit = config.limit();
        this.windowNanos = config.window().toNanos();
        this.time = time;
        this.currentWindow = Math.floorDiv(time.nanoTime(), windowNanos);
    }

    @Override
    public synchronized boolean tryAcquire() {
        long window = Math.floorDiv(time.nanoTime(), windowNanos);
        if (window != currentWindow) {
            currentWindow = window;
            count = 0;
        }
        if (count < limit) {
            count++;
            return true;
        }
        return false;
    }
}
