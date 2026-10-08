package lrucache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class CacheTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        evictsLeastRecentlyUsed();
        getRefreshesRecency();
        putExistingKeyUpdatesAndRefreshes();
        capacityOne();
        ttlExpiresLazily();
        evictionListenerAndStats();
        handWrittenMatchesLinkedHashMap();
        lfuEvictsLeastFrequentThenOldest();
        synchronizedCacheSurvivesContention();
        stripedCacheRespectsTotalCapacity();
        loadingCacheLoadsHotKeyOnce();
        loadingCacheDoesNotCacheFailures();
        System.out.println("All " + passed + " tests passed.");
    }

    static void evictsLeastRecentlyUsed() {
        LruCache<String, Integer> c = new LruCache<>(3);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        c.put("d", 4); // evicts "a"
        assertEquals(Optional.empty(), c.get("a"), "a evicted");
        assertEquals(List.of("d", "c", "b"), c.keysMostRecentFirst(), "order");
        pass("evictsLeastRecentlyUsed");
    }

    static void getRefreshesRecency() {
        LruCache<String, Integer> c = new LruCache<>(3);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        c.get("a");      // a is now most recent, b is least
        c.put("d", 4);   // evicts b, not a
        assertEquals(Optional.of(1), c.get("a"), "a survived because it was read");
        assertEquals(Optional.empty(), c.get("b"), "b evicted");
        pass("getRefreshesRecency");
    }

    static void putExistingKeyUpdatesAndRefreshes() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1); c.put("b", 2);
        c.put("a", 10);  // update, doesn't grow, makes a most recent
        c.put("c", 3);   // evicts b
        assertEquals(2, c.size(), "size unchanged by update");
        assertEquals(Optional.of(10), c.get("a"), "updated value");
        assertEquals(Optional.empty(), c.get("b"), "b evicted");
        pass("putExistingKeyUpdatesAndRefreshes");
    }

    static void capacityOne() {
        LruCache<String, Integer> c = new LruCache<>(1);
        c.put("a", 1); c.put("b", 2);
        assertEquals(List.of("b"), c.keysMostRecentFirst(), "only latest kept");
        assertTrue(c.remove("b") && c.size() == 0 && !c.remove("b"), "remove works and is idempotent");
        pass("capacityOne");
    }

    static void ttlExpiresLazily() {
        MutableClock clock = new MutableClock();
        LruCache<String, String> c = new LruCache<>(10, Duration.ofMinutes(5), clock, (k, v, cause) -> { });
        c.put("session", "alice");
        clock.advance(Duration.ofMinutes(4));
        assertEquals(Optional.of("alice"), c.get("session"), "still valid at 4 min");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(Optional.empty(), c.get("session"), "expired at 5 min");
        assertEquals(0, c.size(), "expired entry removed on access");
        pass("ttlExpiresLazily");
    }

    static void evictionListenerAndStats() {
        List<String> events = new ArrayList<>();
        LruCache<String, Integer> c = new LruCache<>(2, null, new MutableClock(),
                (k, v, cause) -> events.add(cause + ":" + k));
        c.put("a", 1); c.put("b", 2); c.put("a", 11); c.put("c", 3); c.remove("a");
        assertEquals(List.of("REPLACED:a", "CAPACITY:b", "REMOVED:a"), events, "listener events");
        c.get("c"); c.get("zzz");
        CacheStats s = c.stats();
        assertEquals(1L, s.hits(), "hits");
        assertEquals(1L, s.misses(), "misses");
        assertTrue(Math.abs(s.hitRate() - 0.5) < 1e-9, "hit rate 50%");
        pass("evictionListenerAndStats");
    }

    /** Property test: 100k random operations; hand-written LRU must agree with LinkedHashMap's every time. */
    static void handWrittenMatchesLinkedHashMap() {
        Random rnd = new Random(42);
        LruCache<Integer, Integer> mine = new LruCache<>(50);
        LinkedHashMapLruCache<Integer, Integer> reference = new LinkedHashMapLruCache<>(50);
        for (int i = 0; i < 100_000; i++) {
            int key = rnd.nextInt(120);
            switch (rnd.nextInt(3)) {
                case 0 -> { mine.put(key, i); reference.put(key, i); }
                case 1 -> assertEquals(reference.get(key), mine.get(key), "get(" + key + ") at op " + i);
                default -> assertEquals(reference.remove(key), mine.remove(key), "remove(" + key + ")");
            }
            if (mine.size() != reference.size()) throw new AssertionError("size diverged at op " + i);
        }
        pass("handWrittenMatchesLinkedHashMap");
    }

    static void lfuEvictsLeastFrequentThenOldest() {
        LfuCache<String, Integer> c = new LfuCache<>(3);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        c.get("a"); c.get("a"); c.get("b");   // counts: a=3, b=2, c=1
        c.put("d", 4);                        // evicts c (least frequent)
        assertEquals(Optional.empty(), c.get("c"), "c evicted");
        c.put("e", 5);                        // d=1, e new: tie at count 1 -> evict d (older)
        assertEquals(Optional.empty(), c.get("d"), "tie broken by recency");
        assertEquals(Optional.of(1), c.get("a"), "frequent key kept");
        pass("lfuEvictsLeastFrequentThenOldest");
    }

    static void synchronizedCacheSurvivesContention() throws Exception {
        Cache<Integer, Integer> c = new SynchronizedCache<>(new LruCache<>(100));
        AtomicInteger errors = new AtomicInteger();
        runConcurrently(32, 20_000, (t, i) -> {
            try {
                int key = (t * 31 + i) % 500;
                if (i % 2 == 0) c.put(key, i); else c.get(key);
                if (c.size() > 100) errors.incrementAndGet();
            } catch (RuntimeException e) {
                errors.incrementAndGet(); // a broken linked list shows up as NPEs here
            }
        });
        assertEquals(0, errors.get(), "no corruption, never above capacity");
        pass("synchronizedCacheSurvivesContention");
    }

    static void stripedCacheRespectsTotalCapacity() throws Exception {
        StripedCache<Integer, Integer> c = new StripedCache<>(1_000, 16, LruCache::new);
        runConcurrently(16, 10_000, (t, i) -> c.put(t * 100_000 + i, i));
        assertTrue(c.size() <= 1_000, "total size within capacity: " + c.size());
        assertEquals(1_000, c.size(), "full after many inserts");
        pass("stripedCacheRespectsTotalCapacity");
    }

    static void loadingCacheLoadsHotKeyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        LoadingCache<String, String> c = new LoadingCache<>(new SynchronizedCache<>(new LruCache<>(10)), key -> {
            loads.incrementAndGet();
            sleep(100); // pretend this is a slow DB query
            return "value-of-" + key;
        });
        AtomicInteger wrong = new AtomicInteger();
        runConcurrently(100, 1, (t, i) -> { if (!c.get("hot").equals("value-of-hot")) wrong.incrementAndGet(); });
        assertEquals(1, loads.get(), "100 concurrent misses -> 1 load");
        assertEquals(0, wrong.get(), "everyone got the value");
        pass("loadingCacheLoadsHotKeyOnce");
    }

    static void loadingCacheDoesNotCacheFailures() {
        AtomicInteger calls = new AtomicInteger();
        LoadingCache<String, String> c = new LoadingCache<>(new SynchronizedCache<>(new LruCache<>(10)), key -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("DB timeout");
            return "ok";
        });
        try { c.get("k"); throw new AssertionError("expected failure"); } catch (IllegalStateException expected) { }
        assertEquals("ok", c.get("k"), "next call retries the load");
        pass("loadingCacheDoesNotCacheFailures");
    }

    // --- helpers ---

    interface Body { void run(int thread, int iteration) throws Exception; }

    static void runConcurrently(int threads, int iterations, Body body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) body.run(thread, i);
                    return null;
                });
            }
            start.countDown();
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
