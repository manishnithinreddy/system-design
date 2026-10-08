package lrucache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * "Get from cache, or load it", with SINGLE-FLIGHT loading:
 * if 1,000 threads miss on the same key at once, the loader (e.g. a DB query) runs ONCE
 * and all 1,000 wait for that one result. Prevents a cache stampede on hot keys.
 */
public final class LoadingCache<K, V> {
    private final Cache<K, V> cache;               // must be thread-safe
    private final Function<K, V> loader;
    private final Map<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public LoadingCache(Cache<K, V> threadSafeCache, Function<K, V> loader) {
        this.cache = threadSafeCache;
        this.loader = loader;
    }

    public V get(K key) {
        Optional<V> cached = cache.get(key);
        if (cached.isPresent()) return cached.get();

        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return existing.join(); // someone else is loading: wait for their result
        }
        try {
            // Re-check: another thread may have finished loading between our miss and putIfAbsent.
            Optional<V> loadedMeanwhile = cache.get(key);
            if (loadedMeanwhile.isPresent()) {
                mine.complete(loadedMeanwhile.get());
                return loadedMeanwhile.get();
            }
            V value = loader.apply(key);
            cache.put(key, value);
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e); // waiters see the same failure...
            throw e;
        } finally {
            inFlight.remove(key, mine);    // ...and the NEXT caller retries instead of caching the error
        }
    }
}
