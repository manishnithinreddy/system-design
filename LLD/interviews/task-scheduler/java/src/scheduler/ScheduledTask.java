package scheduler;

/**
 * A handle to one scheduled task: what to run, when, and its current state.
 * The mutable fields are only changed by the Scheduler while holding its lock, and only while
 * the task is NOT inside the heap (changing a heap element's sort key in place corrupts the heap).
 */
public final class ScheduledTask {
    public enum State { SCHEDULED, RUNNING, DONE, CANCELLED, DEAD }

    final long id;
    final String name;
    final Task body;
    final Schedule schedule;
    final TaskOptions options;
    private final Scheduler owner;

    volatile long runAt;          // when the dispatcher should start it (planned time, or a retry time)
    long nominalAt;               // when this occurrence was planned (retries keep it; next run is based on it)
    long seq;                     // insertion order: FIFO tie-break between equal runAt
    int failures;                 // failures of the current occurrence
    volatile long lastLagMillis;  // how late the last run started (now - runAt): the key health metric
    volatile State state = State.SCHEDULED;

    ScheduledTask(Scheduler owner, long id, String name, Task body, Schedule schedule, TaskOptions options) {
        this.owner = owner;
        this.id = id;
        this.name = name;
        this.body = body;
        this.schedule = schedule;
        this.options = options;
    }

    /** Lazy cancel: marks the task; the heap entry is thrown away when it reaches the top. */
    public boolean cancel() { return owner.cancel(this); }

    public String name() { return name; }
    public State state() { return state; }
    public long runAt() { return runAt; }
    public long lastLagMillis() { return lastLagMillis; }

    @Override public String toString() { return name + "@" + runAt + "(" + state + ")"; }
}
