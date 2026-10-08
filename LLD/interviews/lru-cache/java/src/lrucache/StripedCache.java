package lrucache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Lock striping: split the cache into N independent segments, each with its own lock.
 * A key always maps to the same segment (by hash), so threads touching different segments
 * never block each other. Trade-off: LRU order is per segment, not global ("approximately LRU").
 */
public final class StripedCache<K, V> implements Cache<K, V> {
    private final List<Cache<K, V>> segments;
    private final int capacity;

    /** @param segmentFactory builds one segment given its capacity, e.g. LruCache::new */
    public StripedCache(int capacity, int segmentCount, IntFunction<Cache<K, V>> segmentFactory) {
        if (segmentCount <= 0 || capacity < segmentCount) {
            throw new IllegalArgumentException("need capacity >= segmentCount > 0");
        }
        this.capacity = capacity;
        this.segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            // spread the remainder so total capacity is exact
            int segCap = capacity / segmentCount + (i < capacity % segmentCount ? 1 : 0);
            segments.add(new SynchronizedCache<>(segmentFactory.apply(segCap)));
        }
    }

    private Cache<K, V> segmentFor(K key) {
        int h = key.hashCode();
        h ^= (h >>> 16); // mix high bits into low bits, like HashMap does
        return segments.get(Math.floorMod(h, segments.size()));
    }

    @Override public Optional<V> get(K key) { return segmentFor(key).get(key); }
    @Override public void put(K key, V value) { segmentFor(key).put(key, value); }
    @Override public boolean remove(K key) { return segmentFor(key).remove(key); }
    @Override public int size() { return segments.stream().mapToInt(Cache::size).sum(); }
    @Override public int capacity() { return capacity; }
}
