package lrucache;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Least-Recently-Used cache with O(1) get and put.
 *
 *   HashMap<K, Node>      : find a node by key in O(1)
 *   doubly linked list    : keep nodes in recency order; move/unlink a node in O(1)
 *
 *   head <-> [most recent] <-> ... <-> [least recent] <-> tail      (head/tail are sentinels)
 *
 * Optional TTL: entries expire after a fixed time since they were written (checked lazily on get).
 * NOT thread-safe: wrap with SynchronizedCache or use StripedCache for concurrent access.
 */
public final class LruCache<K, V> implements Cache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        long expiresAtMillis; // Long.MAX_VALUE = never
        Node<K, V> prev, next;

        Node(K key, V value, long expiresAtMillis) {
            this.key = key;
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> index;
    // Sentinels: always present, never hold data. They remove every "is the list empty / is this the first node" null check.
    private final Node<K, V> head = new Node<>(null, null, Long.MAX_VALUE);
    private final Node<K, V> tail = new Node<>(null, null, Long.MAX_VALUE);

    private final Duration ttl;            // null = no expiry
    private final Clock clock;
    private final EvictionListener<K, V> listener;
    private long hits, misses, evictions;

    public LruCache(int capacity) {
        this(capacity, null, Clock.systemUTC(), (k, v, c) -> { });
    }

    public LruCache(int capacity, Duration ttl, Clock clock, EvictionListener<K, V> listener) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock);
        this.listener = Objects.requireNonNull(listener);
        // Pre-size so the HashMap never resizes: capacity / 0.75 load factor.
        this.index = new HashMap<>((int) (capacity / 0.75f) + 1);
        head.next = tail;
        tail.prev = head;
    }

    @Override
    public Optional<V> get(K key) {
        Node<K, V> node = index.get(key);
        if (node == null) {
            misses++;
            return Optional.empty();
        }
        if (isExpired(node)) {
            unlink(node);
            index.remove(key);
            evictions++;
            misses++;
            listener.onEviction(node.key, node.value, EvictionListener.Cause.EXPIRED);
            return Optional.empty();
        }
        moveToFront(node); // reading makes it "recently used"
        hits++;
        return Optional.of(node.value);
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value"); // null values make "missing" vs "cached null" ambiguous
        Node<K, V> existing = index.get(key);
        if (existing != null) {
            V old = existing.value;
            existing.value = value;
            existing.expiresAtMillis = expiryFromNow();
            moveToFront(existing);
            listener.onEviction(key, old, EvictionListener.Cause.REPLACED);
            return;
        }
        if (index.size() == capacity) {
            evictLeastRecent();
        }
        Node<K, V> node = new Node<>(key, value, expiryFromNow());
        index.put(key, node);
        addAfterHead(node);
    }

    @Override
    public boolean remove(K key) {
        Node<K, V> node = index.remove(key);
        if (node == null) return false;
        unlink(node);
        listener.onEviction(node.key, node.value, EvictionListener.Cause.REMOVED);
        return true;
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    public CacheStats stats() {
        return new CacheStats(hits, misses, evictions);
    }

    /** Keys from most to least recently used. For tests and debugging. */
    public java.util.List<K> keysMostRecentFirst() {
        java.util.List<K> keys = new java.util.ArrayList<>(index.size());
        for (Node<K, V> n = head.next; n != tail; n = n.next) keys.add(n.key);
        return keys;
    }

    // --- linked list operations: each is O(1) because we hold a direct reference to the node ---

    private void evictLeastRecent() {
        Node<K, V> lru = tail.prev;
        unlink(lru);
        index.remove(lru.key);
        evictions++;
        listener.onEviction(lru.key, lru.value, EvictionListener.Cause.CAPACITY);
    }

    private void moveToFront(Node<K, V> node) {
        unlink(node);
        addAfterHead(node);
    }

    private void addAfterHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = node.next = null; // help GC, and make accidental reuse fail fast
    }

    private long expiryFromNow() {
        return ttl == null ? Long.MAX_VALUE : clock.millis() + ttl.toMillis();
    }

    private boolean isExpired(Node<K, V> node) {
        return node.expiresAtMillis != Long.MAX_VALUE && clock.millis() >= node.expiresAtMillis;
    }
}
