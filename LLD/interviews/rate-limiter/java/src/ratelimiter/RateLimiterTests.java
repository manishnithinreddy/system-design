package ratelimiter;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain-Java tests (no JUnit) so the code runs with just javac + java.
 * In a real project these would be JUnit 5 tests.
 */
public final class RateLimiterTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        tokenBucketAllowsBurstThenRefills();
        fixedWindowResetsAtBoundary();
        fixedWindowAllowsDoubleBurstAtBoundary();
        slidingWindowLogBlocksBoundaryBurst();
        slidingWindowCounterSmoothsBoundary();
        keyedLimiterIsolatesKeysAndUsesTiers();
        keyedLimiterEvictsIdleKeys();
        for (Algorithm a : Algorithm.values()) {
            concurrentCallersNeverExceedLimit(a);
        }
        System.out.println("All " + passed + " tests passed.");
    }

    static void tokenBucketAllowsBurstThenRefills() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiter rl = new TokenBucketLimiter(RateLimitConfig.of(Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1)), clock);

        assertEquals(10, countAllowed(rl, 20), "burst up to capacity");
        clock.advance(Duration.ofMillis(100)); // 10 tokens/s * 0.1 s = 1 token
        assertEquals(1, countAllowed(rl, 5), "one token refilled after 100ms");
        clock.advance(Duration.ofSeconds(10)); // refill is capped at capacity
        assertEquals(10, countAllowed(rl, 20), "refill capped at capacity");
        pass("tokenBucketAllowsBurstThenRefills");
    }

    static void fixedWindowResetsAtBoundary() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiter rl = new FixedWindowLimiter(RateLimitConfig.of(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1)), clock);

        assertEquals(5, countAllowed(rl, 10), "limit in first window");
        clock.advance(Duration.ofMillis(999));
        assertEquals(0, countAllowed(rl, 10), "still same window");
        clock.advance(Duration.ofMillis(1));
        assertEquals(5, countAllowed(rl, 10), "new window resets count");
        pass("fixedWindowResetsAtBoundary");
    }

    static void fixedWindowAllowsDoubleBurstAtBoundary() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiter rl = new FixedWindowLimiter(RateLimitConfig.of(Algorithm.FIXED_WINDOW, 5, Duration.ofSeconds(1)), clock);

        clock.advance(Duration.ofMillis(999));
        int first = countAllowed(rl, 5);
        clock.advance(Duration.ofMillis(2)); // 2ms later, new window
        int second = countAllowed(rl, 5);
        // The known weakness: 10 requests within 2ms with a limit of 5/s.
        assertEquals(10, first + second, "fixed window lets 2x limit through around a boundary");
        pass("fixedWindowAllowsDoubleBurstAtBoundary");
    }

    static void slidingWindowLogBlocksBoundaryBurst() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiter rl = new SlidingWindowLogLimiter(RateLimitConfig.of(Algorithm.SLIDING_WINDOW_LOG, 5, Duration.ofSeconds(1)), clock);

        clock.advance(Duration.ofMillis(999));
        int first = countAllowed(rl, 5);
        clock.advance(Duration.ofMillis(2));
        int second = countAllowed(rl, 5);
        assertEquals(5, first + second, "sliding log never exceeds limit in any 1s span");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(5, countAllowed(rl, 10), "allowed again after window slides");
        pass("slidingWindowLogBlocksBoundaryBurst");
    }

    static void slidingWindowCounterSmoothsBoundary() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiter rl = new SlidingWindowCounterLimiter(RateLimitConfig.of(Algorithm.SLIDING_WINDOW_COUNTER, 10, Duration.ofSeconds(1)), clock);

        assertEquals(10, countAllowed(rl, 10), "fill first window");
        clock.advance(Duration.ofMillis(1250)); // 25% into the next window -> previous weighted 75% = 7.5
        int allowed = countAllowed(rl, 10);
        assertEquals(3, allowed, "estimate = 10*0.75 + current; only ~2.5 more allowed");
        pass("slidingWindowCounterSmoothsBoundary");
    }

    static void keyedLimiterIsolatesKeysAndUsesTiers() {
        FakeTimeSource clock = new FakeTimeSource();
        RateLimiterFactory factory = new RateLimiterFactory(clock);
        try (KeyedRateLimiter keyed = new KeyedRateLimiter(
                key -> key.startsWith("premium:")
                        ? RateLimitConfig.of(Algorithm.TOKEN_BUCKET, 100, Duration.ofMinutes(1))
                        : RateLimitConfig.of(Algorithm.TOKEN_BUCKET, 3, Duration.ofMinutes(1)),
                factory, clock, Duration.ofMinutes(10), false)) {

            assertEquals(3, countAllowed(keyed, "free:alice", 10), "free tier limit");
            assertEquals(3, countAllowed(keyed, "free:bob", 10), "other key has its own bucket");
            assertEquals(100, countAllowed(keyed, "premium:carol", 200), "premium tier limit");
        }
        pass("keyedLimiterIsolatesKeysAndUsesTiers");
    }

    static void keyedLimiterEvictsIdleKeys() {
        FakeTimeSource clock = new FakeTimeSource();
        try (KeyedRateLimiter keyed = new KeyedRateLimiter(
                key -> RateLimitConfig.of(Algorithm.FIXED_WINDOW, 1, Duration.ofSeconds(1)),
                new RateLimiterFactory(clock), clock, Duration.ofMinutes(5), false)) {

            keyed.tryAcquire("a");
            keyed.tryAcquire("b");
            clock.advance(Duration.ofMinutes(4));
            keyed.tryAcquire("b"); // b is active, a is idle
            clock.advance(Duration.ofMinutes(2));
            keyed.evictIdle();
            assertEquals(1, keyed.trackedKeys(), "idle key evicted, active key kept");
        }
        pass("keyedLimiterEvictsIdleKeys");
    }

    /** 32 threads hammer one limiter with frozen time: exactly `limit` requests may pass. */
    static void concurrentCallersNeverExceedLimit(Algorithm algorithm) throws Exception {
        FakeTimeSource clock = new FakeTimeSource(); // time frozen -> no refill during the test
        RateLimiter rl = new RateLimiterFactory(clock).create(RateLimitConfig.of(algorithm, 1_000, Duration.ofHours(1)));

        int threads = 32, callsPerThread = 500; // 16,000 attempts for 1,000 permits
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        if (rl.tryAcquire()) allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown(); // release all threads at once to maximise contention
        } // close() waits for all tasks
        assertEquals(1_000, allowed.get(), algorithm + " under contention");
        pass("concurrentCallersNeverExceedLimit[" + algorithm + "]");
    }

    // --- tiny test helpers ---

    private static int countAllowed(RateLimiter rl, int attempts) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) if (rl.tryAcquire()) ok++;
        return ok;
    }

    private static int countAllowed(KeyedRateLimiter rl, String key, int attempts) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) if (rl.tryAcquire(key)) ok++;
        return ok;
    }

    private static void assertEquals(long expected, long actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
