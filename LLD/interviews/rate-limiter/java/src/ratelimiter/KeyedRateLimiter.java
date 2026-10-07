package ratelimiter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Rate limits MANY clients: one {@link RateLimiter} per key (user id, API key, IP...).
 *
 * - Limiters are created lazily with ConcurrentHashMap.computeIfAbsent (atomic: two threads
 *   seeing a new key at the same time still get the same limiter).
 * - The config for a key is looked up via {@code configResolver}, so different tiers
 *   (free / premium) can have different limits.
 * - Idle keys are evicted periodically so memory doesn't grow forever.
 */
public final class KeyedRateLimiter implements AutoCloseable {
    private static final class Entry {
        final RateLimiter limiter;
        volatile long lastAccessNanos;

        Entry(RateLimiter limiter, long now) {
            this.limiter = limiter;
            this.lastAccessNanos = now;
        }
    }

    private final Map<String, Entry> limiters = new ConcurrentHashMap<>();
    private final Function<String, RateLimitConfig> configResolver;
    private final RateLimiterFactory factory;
    private final TimeSource time;
    private final long idleTimeoutNanos;
    private final ScheduledExecutorService evictor;

    public KeyedRateLimiter(Function<String, RateLimitConfig> configResolver,
                            RateLimiterFactory factory,
                            TimeSource time,
                            Duration idleTimeout,
                            boolean startBackgroundEviction) {
        this.configResolver = configResolver;
        this.factory = factory;
        this.time = time;
        this.idleTimeoutNanos = idleTimeout.toNanos();
        if (startBackgroundEviction) {
            this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limiter-evictor");
                t.setDaemon(true); // don't keep the JVM alive just for cleanup
                return t;
            });
            long period = Math.max(1, idleTimeout.toMillis() / 2);
            evictor.scheduleWithFixedDelay(this::evictIdleSafely, period, period, TimeUnit.MILLISECONDS);
        } else {
            this.evictor = null;
        }
    }

    public boolean tryAcquire(String key) {
        long now = time.nanoTime();
        Entry entry = limiters.computeIfAbsent(key,
                k -> new Entry(factory.create(configResolver.apply(k)), now));
        entry.lastAccessNanos = now;
        return entry.limiter.tryAcquire();
    }

    /** Remove limiters not used for idleTimeout. Public so tests can trigger it deterministically. */
    public void evictIdle() {
        long now = time.nanoTime();
        // removeIf on a ConcurrentHashMap view is safe to run alongside tryAcquire.
        limiters.entrySet().removeIf(e -> now - e.getValue().lastAccessNanos > idleTimeoutNanos);
    }

    public int trackedKeys() {
        return limiters.size();
    }

    private void evictIdleSafely() {
        try {
            evictIdle();
        } catch (RuntimeException e) {
            // An exception escaping a scheduled task silently cancels all future runs.
            System.err.println("rate limiter eviction failed: " + e);
        }
    }

    @Override
    public void close() {
        if (evictor != null) evictor.shutdownNow();
    }
}
