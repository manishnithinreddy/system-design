package lrucache;

/** Snapshot of how well the cache is doing. Hit rate is THE metric for any cache. */
public record CacheStats(long hits, long misses, long evictions) {
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
