'use strict';

/**
 * Version 1: LRU using Map's insertion order.
 * A JS Map remembers the order keys were inserted. "Touching" a key = delete it and set it
 * again, which moves it to the END (newest). The FIRST key is then the least recently used.
 * All operations are O(1). This is the idiomatic JS answer.
 */
class MapLruCache {
  #capacity;
  #map = new Map();

  constructor(capacity) {
    if (!(capacity > 0)) throw new Error('capacity must be > 0');
    this.#capacity = capacity;
  }

  get(key) {
    if (!this.#map.has(key)) return undefined;
    const value = this.#map.get(key);
    this.#map.delete(key);
    this.#map.set(key, value); // move to newest
    return value;
  }

  put(key, value) {
    if (this.#map.has(key)) this.#map.delete(key);
    else if (this.#map.size === this.#capacity) {
      const oldest = this.#map.keys().next().value; // first inserted = least recently used
      this.#map.delete(oldest);
    }
    this.#map.set(key, value);
  }

  remove(key) { return this.#map.delete(key); }
  get size() { return this.#map.size; }
  keysMostRecentFirst() { return [...this.#map.keys()].reverse(); }
}

/**
 * Version 2: hand-written hash map + doubly linked list, the same structure as the Java LruCache.
 * Interviewers often ask for this one to see you understand WHY it's O(1).
 */
class LinkedLruCache {
  #capacity;
  #index = new Map(); // key -> node
  #head = { prev: null, next: null }; // sentinel: most recent side
  #tail = { prev: null, next: null }; // sentinel: least recent side

  constructor(capacity) {
    if (!(capacity > 0)) throw new Error('capacity must be > 0');
    this.#capacity = capacity;
    this.#head.next = this.#tail;
    this.#tail.prev = this.#head;
  }

  get(key) {
    const node = this.#index.get(key);
    if (!node) return undefined;
    this.#unlink(node);
    this.#addFront(node);
    return node.value;
  }

  put(key, value) {
    const existing = this.#index.get(key);
    if (existing) {
      existing.value = value;
      this.#unlink(existing);
      this.#addFront(existing);
      return;
    }
    if (this.#index.size === this.#capacity) {
      const lru = this.#tail.prev;
      this.#unlink(lru);
      this.#index.delete(lru.key);
    }
    const node = { key, value, prev: null, next: null };
    this.#index.set(key, node);
    this.#addFront(node);
  }

  remove(key) {
    const node = this.#index.get(key);
    if (!node) return false;
    this.#unlink(node);
    this.#index.delete(key);
    return true;
  }

  get size() { return this.#index.size; }

  keysMostRecentFirst() {
    const keys = [];
    for (let n = this.#head.next; n !== this.#tail; n = n.next) keys.push(n.key);
    return keys;
  }

  #addFront(node) {
    node.prev = this.#head;
    node.next = this.#head.next;
    this.#head.next.prev = node;
    this.#head.next = node;
  }

  #unlink(node) {
    node.prev.next = node.next;
    node.next.prev = node.prev;
  }
}

module.exports = { MapLruCache, LinkedLruCache };
