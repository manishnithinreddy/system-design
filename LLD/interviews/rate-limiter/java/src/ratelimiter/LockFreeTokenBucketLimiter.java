package ratelimiter;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Same algorithm as {@link TokenBucketLimiter}, but without locks: the whole state is an
 * immutable snapshot swapped atomically with compare-and-set (CAS). If another thread changed
 * the state between our read and our write, CAS fails and we retry with fresh state.
 *
 * Worth it only under heavy contention on the SAME key; otherwise synchronized is simpler.
 */
public final class LockFreeTokenBucketLimiter implements RateLimiter {
    private record State(double tokens, long lastRefillNanos) {}

    private final double capacity;
    private final double tokensPerNano;
    private final TimeSource time;
    private final AtomicReference<State> state;

    public LockFreeTokenBucketLimiter(RateLimitConfig config, TimeSource time) {
        this.capacity = config.limit();
        this.tokensPerNano = (double) config.limit() / config.window().toNanos();
        this.time = time;
        this.state = new AtomicReference<>(new State(capacity, time.nanoTime()));
    }

    @Override
    public boolean tryAcquire() {
        while (true) {
            State current = state.get();
            long now = time.nanoTime();
            long elapsed = Math.max(0, now - current.lastRefillNanos());
            double refilled = Math.min(capacity, current.tokens() + elapsed * tokensPerNano);
            long newLast = Math.max(now, current.lastRefillNanos());

            if (refilled < 1) {
                // Nothing to take. No need to write: a later caller will recompute the refill.
                return false;
            }
            State next = new State(refilled - 1, newLast);
            if (state.compareAndSet(current, next)) {
                return true;
            }
            // lost the race -> loop and retry with the new state
        }
    }
}
