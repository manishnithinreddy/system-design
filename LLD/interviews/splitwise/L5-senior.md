# Splitwise — L5 (Senior) LLD Interview

> **Level expectation:** model the variants as a closed type hierarchy, get rounding provably right, make history immutable (ledger + reversals), implement debt simplification with a correctness argument, and make the service safe under retries and concurrency. Prove it with invariant-based tests. Read [L4-mid.md](L4-mid.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Requirements (what's new vs L4)

**🧑‍💻 Candidate:**
- Delete/edit expenses **with history** (activity feed, restore).
- "Simplify debts" suggestion.
- Mobile clients **retry** on bad networks → adding an expense must be **idempotent**.
- Several members edit the same group concurrently.
- Strong correctness guarantees, stated as invariants and tested.

---

## 2. Design

Code: [java/src/splitwise/](java/src/splitwise/) · diagram in the [README](README.md#class-diagram-matches-the-code).

| Decision | Why |
|---|---|
| `sealed interface SplitSpec` with `Equal`, `Exact`, `Percent`, `Shares` records | A **closed** set of variants the service owns. `switch` over them is checked for completeness by the compiler |
| `Splitter` (pure function) | Splitting has no state; easy to test exhaustively |
| `sealed interface LedgerEntry` with `Expense`, `Settlement`, `Reversal` | History is a closed set of event types |
| `GroupLedger` per group, append-only | Balances derived; corrections are new entries |
| `DebtSimplifier` (pure function) | Algorithm isolated from storage |
| `ExpenseService` facade | One entry point for the API layer |

**🧑‍💼 Interviewer:** Why a sealed interface instead of a `SplitStrategy` interface anyone can implement?

**🧑‍💻 Candidate:** Strategy is for **open** extension: other teams plug in their own behaviour. Split types are a **closed** product decision: every client app, the API schema and the database must understand each one. Sealed + records gives me: data-only variants that serialise easily, and an exhaustive `switch` that fails to compile if someone adds `ByItem` and forgets to handle it. ([Sealed interfaces & pattern matching](../../libraries/java/sealed-interfaces-and-pattern-matching.md).)

```java
return switch (spec) {
    case SplitSpec.Equal e   -> largestRemainder(total, equalWeights(e.userIds()));
    case SplitSpec.Exact ex  -> validateSumsTo(total, ex.paise());
    case SplitSpec.Percent p -> largestRemainder(total, checked100(p.percents()));
    case SplitSpec.Shares s  -> largestRemainder(total, positive(s.shares()));
};   // no default: the compiler checks every variant is handled
```

> 📝 **Note:** Being able to explain *when* to use Strategy (open) vs a sealed hierarchy (closed) is a senior modelling signal. Both are "polymorphism"; they solve different problems.

---

## 3. Deep dives

### 3.1 Rounding that always adds up

**Largest remainder method** ([splitting money & rounding](../../concepts/splitting-money-and-rounding.md)):

```text
share_i = total × weight_i / Σweights            (exact, a fraction)
floor_i = ⌊share_i⌋
leftover = total − Σ floor_i                       (always 0 ≤ leftover < number of people)
give +1 paisa to the `leftover` people with the largest (share_i − floor_i); ties → by user id
```

- **Why it's correct:** every floor is ≤ its share, so `leftover ≥ 0`; each fraction is < 1, so `leftover < n`. Exactly `leftover` people get +1 → parts sum to `total`, and **no one is off by more than 1 paisa** from their exact share.
- **Deterministic:** sorting ties by user id means the same input always gives the same output, on every device and server, and in tests.
- Java computes shares with `BigDecimal` (10 decimal places, far beyond what matters). The JS version uses `BigInt` and integer division with remainder, so it's **exact**, with no decimals at all.

### 3.2 Append-only ledger

```java
public sealed interface LedgerEntry permits Expense, Settlement, Reversal { String id(); Instant at(); }
```

| Operation | Ledger effect |
|---|---|
| Add expense | append `Expense` |
| Settle up | append `Settlement` |
| Delete expense | append `Reversal(expenseId)` (cancels its effect) |
| Edit expense | append `Reversal(old)` + new `Expense` |
| Restore | append a new `Expense` copying the old one |

```java
for (LedgerEntry e : entries) {
    switch (e) {
        case Expense x    -> apply(net, x, +1);
        case Settlement s -> { net.merge(s.from(), s.amountPaise(), Long::sum); net.merge(s.to(), -s.amountPaise(), Long::sum); }
        case Reversal r   -> apply(net, expensesById.get(r.reversedExpenseId()), -1);
    }
}
```

- **History for free:** the activity feed ("Bala deleted Dinner") is the ledger.
- **Can't drift:** balances are a pure function of entries. A bug in balance code is fixed by fixing the code; no data repair is needed.
- **Invariant:** `Σ balances == 0` after every operation (each entry adds +x to one side and −x to the other).
- Double-delete is rejected; deleting something that was never added is rejected.

This is **event sourcing** in miniature ([ledgers & event sourcing](../../concepts/ledgers-and-event-sourcing.md)). Your infra analogy: Git (commits, never edits; `git revert` adds a commit) or a database's write-ahead log.

### 3.3 Simplify debts: greedy, and why not optimal

```java
PriorityQueue<Party> creditors = ...;  // owed most first
PriorityQueue<Party> debtors   = ...;  // owe most first
while (!creditors.isEmpty()) {
    Party c = creditors.poll(), d = debtors.poll();
    long x = Math.min(c.amount(), d.amount());
    transfers.add(new Transfer(d.user(), c.user(), x));
    if (c.amount() > x) creditors.add(new Party(c.user(), c.amount() - x));
    if (d.amount() > x) debtors.add(new Party(d.user(), d.amount() - x));
}
```

**Correctness argument:**
- Total owed by debtors = total owed to creditors (balances sum to 0), so the two queues empty together.
- Each iteration fully settles **at least one** person (whoever had the smaller amount) → **at most n − 1** transfers.
- O(n log n) with heaps ([TreeSet & PriorityQueue](../../libraries/java/treeset-and-priorityqueue.md)).

**🧑‍💼 Interviewer:** Is n−1 the minimum?

**🧑‍💻 Candidate:** Not always. The true minimum equals n minus the maximum number of disjoint subgroups whose balances each sum to zero: a group that's internally balanced can settle among itself. Finding that is a subset-sum-style search, which is NP-hard in general. Greedy is fast, at most n−1, and in practice close to optimal. For a typical group of ≤ 15 people, you *could* brute-force the optimal partition (bitmask DP over 2ⁿ subsets) if product really wanted the absolute minimum. Concrete case: balances A +6, B +4, C −3, D −3, E −4. Greedy produces **4** payments (E→A 4, C→B 3, D→A 2, D→B 1: the exact output of the code). The optimum is **3**: B and E balance each other (E→B 4), and A, C, D form another balanced subgroup (C→A 3, D→A 3). Greedy can't "see" those subgroups. ([Greedy algorithms](../../concepts/greedy-algorithms.md).)

**Product caveat:** simplification can make **strangers pay each other** (Dev pays Asha though they never shared a bill). That's why Splitwise makes it an opt-in group setting.

### 3.4 Idempotent `addExpense`

```java
IdempotentRequest previous = requests.get(requestId);
if (previous != null) {
    if (!sameRequest(previous, paidBy, total, spec))
        throw new IllegalStateException("requestId reused for a different expense");
    return previous.result();             // a retry gets the ORIGINAL expense back
}
```

- The **client** generates `requestId` (a UUID when the user taps Save) and reuses it on retries. ([Idempotency](../../../HLD/concepts/idempotency-and-delivery-semantics.md).)
- **Same key, different payload** is rejected loudly: that's a client bug, and silently returning the old result would hide it.
- In production the request map would have a TTL (e.g. 24 h) and live in the database alongside the ledger, ideally written in the same transaction.

### 3.5 Concurrency: lock per group

```java
private final Map<String, GroupLedger> groups = new ConcurrentHashMap<>();   // registry
synchronized LedgerEntry.Expense addExpense(...) { ... }                     // inside GroupLedger
```

- **Granularity:** a group is the natural consistency boundary. Everything that must be atomic (idempotency check + split + append) is inside one group. Different groups never contend.
- `ConcurrentHashMap.putIfAbsent` makes `createGroup` atomic too ([ConcurrentHashMap](../../libraries/java/concurrent-hashmap.md)).
- Test `concurrentExpensesAreNotLost`: 16 threads × 500 expenses into one group → exactly 8,000 entries and the exact expected balance.

### 3.6 Tests built on invariants

| Test | Invariant / scenario |
|---|---|
| `equalSplitHandsOutLeftoverPaise` | ₹100 ÷ 3 = 33.34 + 33.33 + 33.33, deterministic |
| `percentAndSharesAlwaysSumToTotal` | Parts = total; largest fraction gets the extra paisa |
| `invalidSplitsAreRejected` | Validation per variant; `Money.parse("10.001")` throws |
| `balancesFromATrip`, `settleUpAndDeleteAreNewEntries` | Worked example; history grows, never shrinks |
| `addExpenseIsIdempotent` | Retry returns original; mismatched reuse rejected |
| `randomExpensesKeepInvariants` | **300 random groups**: Σ balances = 0; simplification uses ≤ n−1 transfers and settles everyone exactly |
| `concurrentExpensesAreNotLost` | No lost updates under contention |

---

## 4. Follow-ups

**🧑‍💼 Interviewer:** Multiple payers ("I paid ₹600, you paid ₹400 of a ₹1,000 bill")?

**🧑‍💻 Candidate:** Generalise `paidBy` to a map `paidPaise: user → amount` that must sum to the total. Balances already work per user, so `apply()` adds each payer's amount instead of one. The split side is unchanged.

**🧑‍💼 Interviewer:** Balances for a group with 50,000 entries?

**🧑‍💻 Candidate:** Recomputing on every read gets slow eventually. Keep a **cached balance snapshot** updated incrementally on each append (still derived: it can always be rebuilt from the ledger), plus periodic snapshots so a rebuild starts from the last one. That's the [L6](L6-staff.md) persistence discussion.

---

## 5. What the interviewer was evaluating (L5)

- [ ] Sealed variants vs open strategies, with a reason
- [ ] Largest remainder with a correctness argument (sum exact, ≤ 1 paisa error, deterministic)
- [ ] Append-only ledger; deletes/edits as reversals; Σ = 0 invariant
- [ ] Greedy simplification with the ≤ n−1 argument; knows minimum is NP-hard; product caveat
- [ ] Idempotency including mismatched-payload detection
- [ ] Per-group locking as the consistency boundary
- [ ] Property-based tests on invariants

## 6. Common mistakes at this level

| Mistake | Why it hurts at L5 |
|---|---|
| Non-deterministic tie-breaking (HashMap iteration order) | Different results on different machines; flaky tests |
| Deleting expenses physically | No audit trail; can't restore; disputes |
| Claiming greedy gives the minimum number of payments | It doesn't; shows a gap in reasoning |
| A global lock on the whole service | Every group waits on every other |
| Idempotency that ignores payload mismatches | Hides client bugs; wrong expense silently kept |
| Testing only hand-picked examples | Misses rounding/simplification edge cases that random tests find |

⬅️ Previous: [L4-mid.md](L4-mid.md) · ➡️ Next: [L6-staff.md](L6-staff.md)
