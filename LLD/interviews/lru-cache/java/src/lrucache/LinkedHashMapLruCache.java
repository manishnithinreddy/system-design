package lrucache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The "real world" LRU in ~10 lines: LinkedHashMap already IS a hash map + doubly linked list.
 * accessOrder=true moves an entry to the end on every get/put; removeEldestEntry evicts the first.
 * Know BOTH this and the hand-written version: interviewers usually ask for the hand-written one.
 */
public final class LinkedHashMapLruCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LinkedHashMapLruCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LinkedHashMapLruCache.this.capacity;
            }
        };
    }

    @Override public Optional<V> get(K key) { return Optional.ofNullable(map.get(key)); }
    @Override public void put(K key, V value) { map.put(key, value); }
    @Override public boolean remove(K key) { return map.remove(key) != null; }
    @Override public int size() { return map.size(); }
    @Override public int capacity() { return capacity; }
}
