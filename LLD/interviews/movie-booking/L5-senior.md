# Movie Booking — L5 (Senior) LLD Interview

> **Level expectation:** compare concurrency designs with reasons (pessimistic vs optimistic), know why naive per-seat locks deadlock and how ordering fixes it, handle the expiry and payment races precisely, and prove correctness with contention tests that run against every implementation. Read [L4-mid.md](L4-mid.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Requirements (what's new vs L4)

**🧑‍💻 Candidate:**
- **Blockbuster launch:** thousands of users on one show at once. Does one lock per show hold up?
- **Exactly-one-winner** guarantees for contested seats.
- Robust **payment races**: late, duplicate and concurrent confirmations.
- Pluggable **refund policies**; per-user seat limits.

---

## 2. Design

Code: [java/src/moviebooking/](java/src/moviebooking/) · diagram in the [README](README.md#class-diagram-matches-the-code).

The key design move: **`SeatInventory` is an interface** with two implementations, and the service doesn't know which one it uses:

```java
public interface SeatInventory {
    boolean tryHold(List<String> seatIds, String holdId, Instant now, Instant expiresAt);   // all-or-nothing
    boolean confirm(List<String> seatIds, String holdId, String bookingId, Instant now);
    void release(List<String> seatIds, String holdId);
    void cancel(List<String> seatIds, String bookingId);
    List<String> freeSeats(Instant now);
}

new BookingService(clock, Duration.ofMinutes(10), RefundPolicy.STANDARD, LockingSeatInventory::new);
new BookingService(clock, Duration.ofMinutes(10), RefundPolicy.STANDARD, CasSeatInventory::new);
```

The **same test suite** runs against both, which is how you compare concurrency strategies honestly.

---

## 3. Deep dives

### 3.1 Three ways to make "hold these seats" atomic

| Approach | How | Pros | Cons |
|---|---|---|---|
| **A. One lock per show** (pessimistic) | `synchronized` on the show's inventory | Trivially correct; easy to reason about | All bookings for one show are serialised |
| **B. One lock per seat** (pessimistic) | Lock each requested seat, then check and change | Different seats proceed in parallel | **Deadlock** if two users lock seats in different orders |
| **C. CAS per seat + rollback** (optimistic) | Claim seats one by one with compare-and-set; undo on failure | Nobody ever waits → no deadlock; parallel | Rollback logic; wasted work under heavy contention |

([Optimistic vs pessimistic locking](../../concepts/optimistic-vs-pessimistic-locking.md).)

### 3.2 Why per-seat locks deadlock, and the fix

**🧑‍💻 Candidate:** Divya selects C5 then C4; Arjun selects C4 then C5:

```text
Divya:  lock(C5) ✓ ............ lock(C4) ⏳ waits for Arjun
Arjun:  lock(C4) ✓ ............ lock(C5) ⏳ waits for Divya      → both wait forever: DEADLOCK
```

Fix: **always lock in one global order**, e.g. sorted seat IDs. Then both try C4 first; one gets it, the other waits, and no cycle is possible. Alternatives: `tryLock(timeout)` and back off. ([Deadlocks & lock ordering](../../concepts/deadlocks-and-lock-ordering.md).)

> 📝 **Note:** "Sort the resources, lock in that order" is the textbook deadlock-avoidance answer. Expect this follow-up whenever you mention per-item locks.

### 3.3 The optimistic version: [CasSeatInventory.java](java/src/moviebooking/CasSeatInventory.java)

```java
private final Map<String, AtomicReference<SeatState>> seats;      // built once, never modified

public boolean tryHold(List<String> ids, String holdId, Instant now, Instant expiresAt) {
    SeatState.Held mine = new SeatState.Held(holdId, expiresAt);
    List<String> claimed = new ArrayList<>();
    for (String id : ids.stream().sorted().toList()) {
        AtomicReference<SeatState> ref = seats.get(id);
        SeatState current = ref.get();
        if (!SeatState.isFree(current, now) || !ref.compareAndSet(current, mine)) {
            for (String c : claimed) seats.get(c).compareAndSet(mine, SeatState.Available.INSTANCE);  // undo
            return false;
        }
        claimed.add(id);
    }
    return true;
}
```

Why it's correct ([atomics & CAS](../../libraries/java/atomics-and-cas.md)):
- **Exclusive:** `compareAndSet(current, mine)` succeeds only if the seat still holds *exactly* the state we checked. If another thread changed it in between, our CAS fails. Two users can never both move a seat from free to held.
- **All-or-nothing:** on any failure we undo our own claims. The undo uses `compareAndSet(mine, Available)`, which only reverts seats that still hold **our** object, so it never clobbers someone else's state.
- **No deadlock:** nobody waits; a thread either succeeds or gives up immediately.
- **Expired holds are claimable:** `isFree(current, now)` treats an expired `Held` as free, and the CAS replaces exactly that stale object.

**The subtle cost:** while Divya's claims are half-done (C4 claimed, C5 not yet), Arjun may see C4 as taken and fail, even if Divya then fails on C5 and rolls back. Under heavy contention on overlapping seat sets, both can fail where a lock would have let one succeed. Sorting the claim order reduces such collisions. For a typical show, contention is on a few hot seats and either design works.

**🧑‍💼 Interviewer:** Which one would you ship?

**🧑‍💻 Candidate:** **The per-show lock**, unless measurements show contention. It's simpler and easy to reason about. A show has a few hundred seats and a lock hold time of microseconds, which allows thousands of operations per second per show. The CAS version is the answer to "what if one show is hammered", and it's also the in-memory version of the database technique in [L6](L6-staff.md) (conditional updates).

### 3.4 Payment races, precisely

| Race | Handling |
|---|---|
| Two callbacks for the same payment at once | `bookingsByHold.compute(holdId, …)`: atomic per hold; the second sees the first's booking and returns it |
| Callback after the hold expired, seat still free | `confirm` checks `now < expiresAt` → fails → refund. (Strict rule: once expired, the hold is dead even if nobody took the seat, which keeps behaviour predictable) |
| Callback after expiry, someone else now holds the seat | `confirm` requires `Held(holdId = mine)` on **every** seat → fails → refund. Arjun's hold is untouched |
| User releases while payment is in flight | Release only reverts seats still held by that hold; a confirm that comes later fails cleanly → refund |

In the CAS version, confirm is **two-phase**: first verify every seat is still `Held(mine)` and unexpired, then swap each to `Booked`. If a swap fails midway (only possible if the hold expired at that instant and someone grabbed the seat), already-booked seats are swapped back.

### 3.5 Tests that run against both implementations

| Test | What it proves |
|---|---|
| `holdThenConfirm`, `releaseAndCancelFreeSeats` | Happy paths, prices, refunds |
| `seatCannotBeHeldTwice`, `holdIsAllOrNothing` | Exclusivity; rollback leaves other seats free |
| `expiredHoldFreesSeatsAndCannotBeConfirmed` | Lazy expiry; late confirm can't steal a re-held seat |
| `confirmIsIdempotent` | Duplicate callbacks |
| `concurrentHoldsNeverOverlap` | 64 threads × 50 random 1–4 seat holds on 40 seats: **no seat in two successful holds**, and free-seat count matches |
| `everyoneWantsTheSameSeats` | 200 threads, same 2 seats, same instant: **exactly one winner** |

---

## 4. Follow-ups

**🧑‍💼 Interviewer:** "Don't leave a single empty seat between booked seats" rule?

**🧑‍💻 Candidate:** A validation rule applied in `holdSeats` before calling the inventory: simulate the hold on the current seat map and reject if it creates an isolated single seat in a row. Make it a pluggable `SeatSelectionRule` list (Strategy/Chain), since theatres differ. The rule must be evaluated **inside** the same atomic step as the hold, or two users could each leave a legal gap that together becomes illegal. Practically: run rules inside the per-show lock.

**🧑‍💼 Interviewer:** Why not a scheduled job that releases holds after 10 minutes?

**🧑‍💻 Candidate:** Fine as tidy-up ([scheduled executor](../../libraries/java/scheduled-executor-service.md)), but correctness must not depend on it: a job running 30 seconds late would block seats for 30 seconds. With lazy expiry, the hold is invalid the instant `now ≥ expiresAt`, whatever the job does.

**🧑‍💼 Interviewer:** Memory for millions of show-seat states in a cache?

**🧑‍💻 Candidate:** For a seat-map *display* cache, one bit per seat ("free or not") in a `BitSet` is ~50 bytes per 400-seat show instead of kilobytes of objects ([BitSet](../../libraries/java/bitset-and-compact-state.md)). The authoritative state (with hold ids and expiry) stays in the inventory/DB.

---

## 5. What the interviewer was evaluating (L5)

- [ ] Inventory behind an interface; same tests for each implementation
- [ ] Compared per-show lock, per-seat locks and CAS with real trade-offs
- [ ] Explained the per-seat deadlock and the lock-ordering fix
- [ ] CAS correctness argument: exclusivity, rollback that can't clobber others, no waiting
- [ ] Knew the optimistic design's failure mode under overlapping contention
- [ ] Precise handling of every payment race; refunds on failure
- [ ] Contention tests with exact invariants (no overlap, exactly one winner)

## 6. Common mistakes at this level

| Mistake | Why it hurts at L5 |
|---|---|
| Per-seat locks acquired in user-selection order | Deadlock under load |
| CAS without rollback | Partial holds leak seats |
| Rollback with a plain `set(Available)` | Can overwrite another user's newer hold |
| Choosing lock-free "for performance" without measuring | Complexity without evidence; harder reviews |
| Expiry enforced only by a background job | Late job = blocked seats |
| Confirm that checks "is the seat held?" but not "by me?" | Late payments steal other people's seats |

⬅️ Previous: [L4-mid.md](L4-mid.md) · ➡️ Next: [L6-staff.md](L6-staff.md)
