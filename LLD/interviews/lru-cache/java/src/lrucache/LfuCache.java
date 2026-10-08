package lrucache;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Least-FREQUENTLY-used cache, O(1) per operation.
 * Evicts the key with the fewest accesses; among ties, the least recently used one.
 *
 *   values    : key -> value
 *   counts    : key -> access count
 *   buckets   : count -> keys with that count, in LRU order (LinkedHashSet keeps insertion order)
 *   minCount  : smallest count present, so we know which bucket to evict from without searching
 *
 * NOT thread-safe.
 */
public final class LfuCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, V> values = new HashMap<>();
    private final Map<K, Integer> counts = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
    private int minCount = 0;

    public LfuCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    @Override
    public Optional<V> get(K key) {
        V value = values.get(key);
        if (value == null) return Optional.empty();
        touch(key);
        return Optional.of(value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (values.containsKey(key)) {
            values.put(key, value);
            touch(key);
            return;
        }
        if (values.size() == capacity) {
            LinkedHashSet<K> least = buckets.get(minCount);
            K victim = least.iterator().next(); // oldest among the least frequent
            least.remove(victim);
            if (least.isEmpty()) buckets.remove(minCount);
            values.remove(victim);
            counts.remove(victim);
        }
        values.put(key, value);
        counts.put(key, 1);
        buckets.computeIfAbsent(1, c -> new LinkedHashSet<>()).add(key);
        minCount = 1; // a brand-new key always has the lowest count
    }

    @Override
    public boolean remove(K key) {
        if (!values.containsKey(key)) return false;
        int count = counts.remove(key);
        values.remove(key);
        LinkedHashSet<K> bucket = buckets.get(count);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            buckets.remove(count);
            // Rare path, O(number of distinct counts). get/put stay O(1).
            if (minCount == count) minCount = buckets.keySet().stream().min(Integer::compare).orElse(0);
        }
        return true;
    }

    private void touch(K key) {
        int count = counts.get(key);
        LinkedHashSet<K> bucket = buckets.get(count);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            buckets.remove(count);
            if (minCount == count) minCount = count + 1;
        }
        counts.put(key, count + 1);
        buckets.computeIfAbsent(count + 1, c -> new LinkedHashSet<>()).add(key);
    }

    @Override public int size() { return values.size(); }
    @Override public int capacity() { return capacity; }
}
