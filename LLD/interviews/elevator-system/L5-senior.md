# Elevator System — L5 (Senior) LLD Interview

> **Level expectation:** a design that's correct in the tricky cases (direction-aware pickup, turnaround points, maintenance), with a dispatch rule you can justify and swap, and a concurrency model you can explain in one sentence. You use patterns where they earn their place and prove behaviour with deterministic tests. Read [L4-mid.md](L4-mid.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Requirements (what's new vs L4)

**🧑‍💻 Candidate:**
- Don't pick up people going the **wrong direction**.
- **Dispatch** hall calls with a cost estimate, not just distance.
- **Maintenance:** remove a car, reassign its unserved hall calls; if every car is out, calls wait.
- **Displays/monitoring** get events.
- Buttons are pressed from **many threads**; the design must be safe and still deterministic in tests.

---

## 2. Design and patterns

Code: [java/src/elevator/](java/src/elevator/) · diagram in the [README](README.md#class-diagram-matches-the-code).

| Pattern | Where | Why ([design patterns](../../concepts/design-patterns.md)) |
|---|---|---|
| **Facade** | `ElevatorSystem` | Buttons and displays see a tiny API |
| **Strategy** | `ElevatorSelectionStrategy` (`NearestCarStrategy`, `LeastBusyStrategy`) | Dispatch rules differ per building and time of day |
| **Command** | `sealed interface Command` with `HallCall`, `CarCall`, `SetMaintenance` records | Button presses become queued, ordered, replayable objects |
| **State** (lightweight) | `ElevatorStatus` enum + `step()` | Four states, a handful of transitions; an enum is enough |
| **Observer** | `ElevatorListener` | Displays/metrics subscribe to events |

**🧑‍💻 Candidate:** Not used: a `State` class per status (overkill for 4 states), a Singleton system, or subclasses per elevator type.

---

## 3. Deep dives

### 3.1 Direction-aware stops

**🧑‍💻 Candidate:** Each car keeps two sorted sets ([TreeSet](../../libraries/java/treeset-and-priorityqueue.md)):

| Set | Contains |
|---|---|
| `upStops` | car calls above the car + hall calls where someone pressed **▲** |
| `downStops` | car calls below the car + hall calls where someone pressed **▼** |

```java
Integer nextTarget() {
    switch (direction) {
        case UP -> {
            Integer ahead = upStops.ceiling(floor);
            if (ahead != null) return ahead;
            // no up-stops ahead, but someone ABOVE wants to go DOWN: go up to the highest one, turn there
            if (!downStops.isEmpty() && downStops.last() >= floor) return downStops.last();
            return turnAround(Direction.DOWN);
        }
        case DOWN -> { /* mirror image */ }
        default -> { return nearestOfAnyStop(); }   // IDLE
    }
}
```

The subtle case is the **turnaround point**: idle car at 0, someone on 7 presses ▼. 7 is in `downStops`, but the car must travel **up** to reach it. The `downStops.last() >= floor` branch handles it: go up to the highest down-request, serve it, and switch direction to DOWN there. Test: `idleCarGoesUpToADownCall`.

When the car arrives at a floor, `serveCurrentFloor()` opens the doors only if the floor is a stop **for the current direction**, or the turnaround point of the sweep. Test: `downCallIsNotPickedUpWhileGoingUp` (car going to 9 passes a ▼ caller on 5; serves 9 first, then 5).

### 3.2 Dispatch: a cost function

**🧑‍💻 Candidate:** "Nearest car" by raw distance is wrong in a common case: a car 1 floor away but moving *away* from you is far worse than an idle car 4 floors away. [NearestCarStrategy](java/src/elevator/NearestCarStrategy.java) estimates travel:

| Car state relative to the call | Estimated travel |
|---|---|
| Idle | `|car − caller|` |
| Moving toward the caller **and** caller wants the same direction | `|caller − car|` (picks them up on the way) |
| Anything else | finish the sweep, then come back: `|sweepEnd − car| + |sweepEnd − caller|` |

Plus `pendingStops` as a penalty (each stop costs door time). Test: `carAlreadyOnTheWayBeatsIdleCar`.

```java
return elevators.stream()
        .filter(Elevator::inService)                        // filter...
        .min(comparingInt((Elevator e) -> cost(e, call))    // ...then score
             .thenComparingInt(Elevator::id));              // deterministic tie-break
```

**🧑‍💼 Interviewer:** Once assigned, does a call ever move to a better car?

**🧑‍💻 Candidate:** Not in this version: assignment is final, which keeps it simple and predictable. Real controllers re-evaluate every second or so ("hall call reallocation") because conditions change: a car fills up, or another frees up. That's an extension point: the system could re-run the strategy for unserved hall calls each tick. The cost is "car changes" that confuse waiting passengers watching the lit-up car.

### 3.3 Concurrency: commands + a single writer

**🧑‍💼 Interviewer:** Buttons are pressed from many threads while cars move. How do you keep this safe?

**🧑‍💻 Candidate:** Options:

| Option | Verdict |
|---|---|
| `synchronized` everything (L4) | Correct, simple. But any slow listener or strategy holds up every button |
| A thread per elevator, locks on shared stop sets | Most bug-prone: dispatch reads all cars' state while they change |
| **Single writer** ✅ | Button presses become `Command`s on a thread-safe queue; **one** simulation thread drains the queue at the start of each `tick()` and is the **only** code that touches elevator state |

```java
private final BlockingQueue<Command> inbox = new LinkedBlockingQueue<>();

public void submit(Command c) { inbox.add(c); }          // any thread: just enqueue

public void tick() {                                       // simulation thread only
    List<Command> batch = new ArrayList<>();
    inbox.drainTo(batch);                                  // take everything pressed since last tick
    for (Command c : batch) apply(c);
    retryWaiting();
    for (Elevator e : elevators) e.step(tick, this::emit);
    publishSnapshots();                                    // immutable copies for other threads
}
```

- **No locks on elevator state at all:** `Elevator` and its `TreeSet`s are plain, non-thread-safe classes, and that's fine because only one thread ever touches them. ([Single-writer principle](../../concepts/single-writer-principle.md), [blocking queues](../../libraries/java/blocking-queues-and-producer-consumer.md).)
- **Reading from other threads:** displays call `snapshots()`, an immutable `List<ElevatorSnapshot>` stored in a `volatile` field after each tick. `volatile` guarantees other threads see the newest list. Each list is immutable, so it can never be seen half-updated.
- **Pattern matching on the sealed `Command`:** `switch (command) { case HallCall c -> …; case CarCall c -> …; case SetMaintenance m -> … }`. The compiler checks every command type is handled.
- This is the same idea as Node's event loop, Redis's single-threaded command execution, and a Kafka partition consumer: **one owner, a queue in front**.

Test: `concurrentButtonPressesAreAllServed`. 8 threads press 4,000 random buttons while the simulation thread ticks; every floor with a hall call must see a door opening.

> 📝 **Note:** The one-sentence answer, "*callers only enqueue commands; one thread owns and mutates all state*", is the kind of crisp concurrency story interviewers remember.

### 3.4 Maintenance and degraded mode

```java
case Command.SetMaintenance m -> {
    if (m.on() && e.inService()) {
        e.enterMaintenance().forEach(this::assign);   // returns its unserved hall calls → other cars
    }
    ...
}

private void assign(Command.HallCall call) {
    Optional<Elevator> car = strategy.select(elevators, call);
    if (car.isPresent()) car.get().addHallCall(call);
    else waiting.add(call);                            // every car is out: keep it, retry next tick
}
```

- Car calls of a car entering maintenance are dropped: they belonged to people inside that car.
- **Calls are never lost:** with all cars out, hall calls wait in `waiting` and are served when a car returns. Tests: `maintenanceCarIsSkippedAndItsCallsReassigned`, `callsWaitWhenEveryCarIsInMaintenance`.
- To know *which* calls to reassign, each car tracks `assignedHallCalls` separately from its stop sets (a floor in `downStops` could be a car call or a hall call).

### 3.5 Observers

```java
@FunctionalInterface
public interface ElevatorListener { void onEvent(ElevatorEvent event); }
public record ElevatorEvent(long tick, int elevatorId, Type type, int floor) {
    public enum Type { MOVED, DOORS_OPENED, MAINTENANCE_ON, MAINTENANCE_OFF }
}
```

Listeners run on the simulation thread, so they must be quick; a throwing listener is caught and logged so it can't stop the elevators.

### 3.6 Testing strategy

| Test | Scenario |
|---|---|
| `servesCarCallsInLookOrder` | Pressed 5, 2, 8 → served 2, 5, 8 |
| `requestBehindIsServedAfterReversal` | Going to 8, someone presses 2 at floor 4 → 8 then 2 |
| `downCallIsNotPickedUpWhileGoingUp` | Direction-aware pickup |
| `idleCarGoesUpToADownCall` | Turnaround point |
| `nearestIdleCarIsChosen`, `carAlreadyOnTheWayBeatsIdleCar` | Dispatch cost |
| maintenance tests | Reassignment, waiting |
| `concurrentButtonPressesAreAllServed` | Thread-safety + liveness (every request eventually served) |

All are deterministic except the concurrency test, whose assertion ("every requested floor is eventually visited") holds regardless of thread timing.

---

## 4. Follow-ups

**🧑‍💼 Interviewer:** Morning rush: everyone in the lobby goes up. Does your dispatcher handle it well?

**🧑‍💻 Candidate:** Poorly. Cars spread out after dropping people off and come back slowly. Better strategies for that time of day: **return idle cars to the lobby** (a parking policy), and **zoning** (car A serves floors 1–10, car B 11–20) so each trip has fewer stops. Since dispatch is a Strategy, the system can switch strategy by time of day. ([Scheduling algorithms](../../concepts/scheduling-algorithms.md).)

**🧑‍💼 Interviewer:** Capacity: a full car shouldn't take more hall calls.

**🧑‍💻 Candidate:** Add `load` to the elevator (from a weight sensor event) and make the strategy's filter step skip cars above ~80% load. If a full car arrives at a hall call anyway, it shouldn't clear that call: leave it unserved so it gets reassigned.

**🧑‍💼 Interviewer:** How would the JS version differ?

**🧑‍💻 Candidate:** [js/elevator.js](js/elevator.js): same design. No `TreeSet` in JS, so I use a tiny sorted array class; with ≤ 100 floors, O(n) inserts don't matter ([sorted collections in JS](../../libraries/js/sorted-collections-in-js.md)). The command queue is a plain array: Node is single-threaded, so the event loop *is* the single writer ([event loop](../../libraries/js/event-loop-and-concurrency.md)). In production, a `setInterval` would call `tick()`, but the logic itself never touches timers, so tests stay instant ([async & timers](../../libraries/js/async-await-and-timers.md)).

---

## 5. What the interviewer was evaluating (L5)

- [ ] Direction-aware stop sets, and the turnaround point handled correctly
- [ ] Dispatch cost function that accounts for direction and sweep, plus deterministic tie-breaks
- [ ] Concurrency model explained in one sentence; no locks on elevator state; safe snapshots via `volatile` + immutable lists
- [ ] Commands as a sealed hierarchy with exhaustive `switch`
- [ ] Maintenance reassignment; calls never lost
- [ ] Observers isolated from failures
- [ ] Deterministic scenario tests + a liveness test under concurrency
- [ ] Extension thinking: reallocation, parking/zoning, capacity

## 6. Common mistakes at this level

| Mistake | Why it hurts at L5 |
|---|---|
| One stop set per car | Wrong-direction pickups |
| Missing the turnaround case (idle car, ▼ call above) | Car never reaches the caller, or goes the wrong way |
| Thread per elevator + shared mutable state + ad-hoc locks | Hard-to-reproduce bugs; deadlocks between dispatcher and cars |
| Dispatch by raw distance | Sends cars moving away from the caller |
| Losing hall calls when every car is out of service | People wait forever with a lit button |
| Listeners able to throw into the simulation loop | One broken display stops the building |

⬅️ Previous: [L4-mid.md](L4-mid.md) · ➡️ Next: [L6-staff.md](L6-staff.md)
