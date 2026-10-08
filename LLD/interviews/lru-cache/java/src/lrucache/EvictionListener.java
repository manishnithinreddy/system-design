package lrucache;

/** Called when an entry leaves the cache. Useful for metrics, closing resources, write-behind. */
@FunctionalInterface
public interface EvictionListener<K, V> {
    enum Cause { CAPACITY, EXPIRED, REPLACED, REMOVED }

    void onEviction(K key, V value, Cause cause);
}
