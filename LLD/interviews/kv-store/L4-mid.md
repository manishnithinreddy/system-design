# KV Store — L4 (Mid-level / SDE2) LLD Interview

> **Level expectation:** implement the commands correctly, make COUNT O(1), support nested transactions efficiently (undo logs rather than copying everything), and handle the edge cases (rollback with no transaction, deleting a missing key, commit semantics). Clean, testable code.

> 🆕 New to transactions or Redis? Read [00-understand-the-product.md](00-understand-the-product.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Clarifying requirements

**🧑‍💼 Interviewer:** Implement an in-memory database with `SET`, `GET`, `DELETE`, `COUNT`, and transactions with `BEGIN`, `ROLLBACK`, `COMMIT`.

**🧑‍💻 Candidate:** Let me pin down the semantics:
- `COUNT v`: number of keys whose value is exactly `v`?
- Can transactions be **nested**? Does `ROLLBACK` undo only the innermost?
- `COMMIT`: commits the innermost transaction, or all open ones?
- What do `ROLLBACK`/`COMMIT` return with no open transaction?
- Data size: does it matter if `BEGIN` is expensive?

**🧑‍💼 Interviewer:** Yes to COUNT. Nested, and rollback undoes the innermost. Commit the innermost (it becomes part of its parent). Print `NO TRANSACTION`. Assume millions of keys, so `BEGIN` must be cheap.

**🧑‍💻 Candidate:**

**Functional:** `SET k v`, `GET k` (`NULL` if missing), `DELETE k`, `COUNT v`, `BEGIN`, `ROLLBACK`, `COMMIT`, with nesting.

**Non-functional:** all data commands O(1); `BEGIN` O(1); `ROLLBACK`/`COMMIT` proportional to keys changed in that transaction, not total data size.

> 📝 **Note:** "Commit innermost or all?" is a real ambiguity; different versions of this question answer differently. Asking it shows care, and changes the code.

---

## 2. Core entities

| Piece | Responsibility |
|---|---|
| `KeyValueStore` | Data, the value→count index, the transaction stack |
| `CommandProcessor` | Parse text commands and format output (`NULL`, `NO TRANSACTION`) |
| `Entry` (record) | A value (plus expiry in L5) |
| `NoTransactionException` | Signal for `ROLLBACK`/`COMMIT` with nothing open |

Separating parsing from storage keeps the store reusable (e.g. from a network server or tests) and testable without string handling.

---

## 3. Interfaces

```java
public final class KeyValueStore {
    public Optional<String> get(String key);
    public void set(String key, String value);
    public boolean delete(String key);
    public int count(String value);
    public void begin();
    public void rollback();     // throws NoTransactionException
    public void commit();       // throws NoTransactionException
}
```

---

## 4. Class diagram

See the [README](README.md#class-diagram-matches-the-code). At L4: `CommandProcessor → KeyValueStore`, with three maps/stacks inside the store.

---

## 5. Deep dives

### 5.1 COUNT in O(1)

**🧑‍💻 Candidate:** Keep a second map, `value → number of keys with that value`, and update it on **every** change:

```java
private void rawPut(String key, Entry e) {
    Entry old = data.put(key, e);
    if (old != null) decrement(old.value());        // the key no longer has its old value
    valueCounts.merge(e.value(), 1, Integer::sum);
}
private void rawRemove(String key) {
    Entry old = data.remove(key);
    if (old != null) decrement(old.value());
}
private void decrement(String value) {
    valueCounts.computeIfPresent(value, (v, n) -> n == 1 ? null : n - 1);   // drop zero entries
}
```

The overwrite case (`SET a 10` then `SET a 20`) is the classic bug: forgetting to decrement 10.

### 5.2 Transactions: why not copy the map?

**🧑‍💻 Candidate:** The simplest idea: on `BEGIN`, copy the whole map; on `ROLLBACK`, restore the copy. Correct, but O(n) per `BEGIN`: with a million keys, every transaction copies a million entries. That's the naive version (I use it as the reference model in tests).

**Undo log instead** ([undo & redo logs](../../concepts/undo-logs-and-redo-logs.md)): each `BEGIN` pushes an empty map. When a key is changed inside that layer **for the first time**, record its original value (or "absent"):

```java
private final Deque<Map<String, Optional<Entry>>> undoStack = new ArrayDeque<>();

public void begin() { undoStack.push(new LinkedHashMap<>()); }

private void write(String key, Entry newEntry) {
    if (!undoStack.isEmpty()) {
        undoStack.peek().putIfAbsent(key, Optional.ofNullable(data.get(key)));   // first touch only
    }
    if (newEntry == null) rawRemove(key); else rawPut(key, newEntry);
}

public void rollback() {
    if (undoStack.isEmpty()) throw new NoTransactionException();
    undoStack.pop().forEach((key, original) -> {
        if (original.isPresent()) rawPut(key, original.get()); else rawRemove(key);
    });
}
```

- `putIfAbsent` matters: `SET a 1; SET a 2; SET a 3` in one layer must restore the value from **before** the layer, not `2`.
- `Optional.empty()` means "the key didn't exist", so rollback deletes it.
- Rollback goes through `rawPut`/`rawRemove`, so `COUNT` stays correct automatically.

### 5.3 Commit of a nested transaction

**🧑‍💻 Candidate:** Committing the inner layer means its changes now belong to the outer transaction, which can still be rolled back. So merge the inner layer's notes into the parent, **keeping the parent's own note** when both touched a key (the parent's note is older):

```java
public void commit() {
    if (undoStack.isEmpty()) throw new NoTransactionException();
    Map<String, Optional<Entry>> layer = undoStack.pop();
    if (!undoStack.isEmpty()) layer.forEach(undoStack.peek()::putIfAbsent);
    // outermost: nothing to keep; changes are already in `data`
}
```

Walk-through: `BEGIN; SET a 1; BEGIN; SET a 2; COMMIT; ROLLBACK` → the inner note "a was 1" is dropped (parent already has "a was absent"), and the outer rollback removes `a` entirely. Test: `rollbackWithoutTransaction`.

### 5.4 Complexity

| Operation | Time |
|---|---|
| `SET`/`GET`/`DELETE`/`COUNT` | O(1) average (hash maps) |
| `BEGIN` | O(1) |
| `ROLLBACK` / nested `COMMIT` | O(keys changed in that layer) |
| Memory for transactions | O(keys changed in open layers) |

---

## 6. Follow-ups

**🧑‍💼 Interviewer:** How did you test it?

**🧑‍💻 Candidate:** The classic sequences, plus a **property test** ([KeyValueStoreTests.java](java/src/kvstore/KeyValueStoreTests.java) `matchesNaiveCopyModel`): 20,000 random operations against a deliberately naive model that copies the whole map on `BEGIN`. The naive model is obviously correct, so any disagreement is a bug in the clever version. I checked that it catches a real bug: changing the nested-commit merge from `putIfAbsent` to `put` makes the tests fail.

**🧑‍💼 Interviewer:** What about a command like `INCR`?

**🧑‍💻 Candidate:** It's a read + `write()`. Because everything changes through `write()`, the undo log, COUNT index (and later the log file) all handle it automatically. That's the payoff of a single write path.

**🧑‍💼 Interviewer:** In JavaScript?

**🧑‍💻 Candidate:** Same structure with `Map`s and an array as the stack ([js/kvstore.js](js/kvstore.js)). `null` instead of `Optional.empty()` for "absent".

---

## 7. What the interviewer was evaluating (L4)

- [ ] Clarified nesting and commit semantics
- [ ] O(1) COUNT with correct overwrite/delete handling
- [ ] Undo-log stack instead of full copies; `putIfAbsent` for first-touch
- [ ] Correct nested commit merge
- [ ] Parsing separated from storage
- [ ] Stated complexity; tested against a naive model

## 8. Common mistakes at this level

| Mistake | Why it's wrong |
|---|---|
| Copy the whole map on `BEGIN` | O(n) per transaction |
| Recording the value before *every* change instead of the first | Rollback restores an intermediate value |
| Nested commit that overwrites the parent's notes | Outer rollback restores the wrong value |
| Forgetting to decrement COUNT on overwrite | Counts drift upward |
| Rollback writing directly to `data` (bypassing count updates) | COUNT wrong after rollback |
| `COUNT` by scanning all values | O(n) per call |

➡️ Next: [L5-senior.md](L5-senior.md)
