package ratelimiter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding window log: remember the timestamp of every accepted request in the last window.
 * Exact (no boundary burst), but memory is O(limit) per key: limit=10,000/hour means
 * storing up to 10,000 longs for EACH client.
 */
public final class SlidingWindowLogLimiter implements RateLimiter {
    private final long limit;
    private final long windowNanos;
    private final TimeSource time;

    // guarded by "this"; oldest timestamp at the head
    private final Deque<Long> log = new ArrayDeque<>();

    public SlidingWindowLogLimiter(RateLimitConfig config, TimeSource time) {
        this.limit = config.limit();
        this.windowNanos = config.window().toNanos();
        this.time = time;
    }

    @Override
    public synchronized boolean tryAcquire() {
        long now = time.nanoTime();
        while (!log.isEmpty() && now - log.peekFirst() >= windowNanos) {
            log.pollFirst();
        }
        if (log.size() < limit) {
            log.addLast(now);
            return true;
        }
        return false;
    }
}
