package pubsub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Routes a topic like "orders.eu.created" to every pattern that matches it. Topics are dot-separated words;
 * in a pattern, "*" matches exactly one word and "#" matches zero or more words (RabbitMQ topic-exchange rules).
 * A trie (prefix tree, one node per word) means matching costs about one step per word of the topic,
 * instead of testing every pattern ever subscribed.
 */
final class TopicTrie<T> {
    private static final class Node<T> {
        final Map<String, Node<T>> children = new HashMap<>();
        final List<T> values = new ArrayList<>();
    }

    private final Node<T> root = new Node<>();
    private final ReadWriteLock rw = new ReentrantReadWriteLock();   // many publishers read, subscribe rarely writes

    static String[] words(String topicOrPattern) {
        String[] w = topicOrPattern.split("\\.", -1);
        for (String s : w) if (s.isEmpty()) throw new IllegalArgumentException("empty word in '" + topicOrPattern + "'");
        return w;
    }

    void add(String pattern, T value) {
        rw.writeLock().lock();
        try {
            Node<T> n = root;
            for (String w : words(pattern)) n = n.children.computeIfAbsent(w, k -> new Node<>());
            n.values.add(value);
        } finally { rw.writeLock().unlock(); }
    }

    void remove(String pattern, T value) {
        rw.writeLock().lock();
        try {
            Node<T> n = root;
            for (String w : words(pattern)) {
                n = n.children.get(w);
                if (n == null) return;
            }
            n.values.remove(value);   // empty nodes are kept: fine for a demo, a real broker would prune them
        } finally { rw.writeLock().unlock(); }
    }

    List<T> match(String topic) {
        String[] words = words(topic);
        Set<T> out = new LinkedHashSet<>();   // a set: "a.#" and "#" may both lead to the same subscription
        rw.readLock().lock();
        try {
            collect(root, words, 0, out);
        } finally { rw.readLock().unlock(); }
        return new ArrayList<>(out);
    }

    private void collect(Node<T> n, String[] words, int i, Set<T> out) {
        Node<T> hash = n.children.get("#");
        if (hash != null) for (int j = i; j <= words.length; j++) collect(hash, words, j, out);   // "#" eats 0..all words
        if (i == words.length) {
            out.addAll(n.values);
            return;
        }
        Node<T> exact = n.children.get(words[i]);
        if (exact != null) collect(exact, words, i + 1, out);
        Node<T> star = n.children.get("*");
        if (star != null) collect(star, words, i + 1, out);
    }
}
