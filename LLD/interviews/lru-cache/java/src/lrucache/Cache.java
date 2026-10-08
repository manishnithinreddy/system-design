package lrucache;

import java.util.Optional;

/** A bounded key-value cache. Implementations decide WHAT to evict when full. */
public interface Cache<K, V> {
    Optional<V> get(K key);

    void put(K key, V value);

    boolean remove(K key);

    int size();

    int capacity();
}
