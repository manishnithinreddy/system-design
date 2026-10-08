package lrucache;

import java.util.Optional;

/**
 * Decorator: makes ANY cache thread-safe with one lock.
 * Note get() must be locked too: in an LRU, a read MOVES the node in the list (it's a write!).
 */
public final class SynchronizedCache<K, V> implements Cache<K, V> {
    private final Cache<K, V> delegate;
    private final Object lock = new Object();

    public SynchronizedCache(Cache<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override public Optional<V> get(K key) { synchronized (lock) { return delegate.get(key); } }
    @Override public void put(K key, V value) { synchronized (lock) { delegate.put(key, value); } }
    @Override public boolean remove(K key) { synchronized (lock) { return delegate.remove(key); } }
    @Override public int size() { synchronized (lock) { return delegate.size(); } }
    @Override public int capacity() { return delegate.capacity(); }
}
