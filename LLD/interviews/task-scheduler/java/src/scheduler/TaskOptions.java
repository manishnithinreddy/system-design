package scheduler;

/** Per-task settings. Higher priority runs first among tasks that are due at the same moment. */
public record TaskOptions(int priority, RetryPolicy retry, MisfirePolicy misfire) {
    public static final TaskOptions DEFAULT = new TaskOptions(0, RetryPolicy.NONE, MisfirePolicy.FIRE_ONCE_NOW);

    public TaskOptions withPriority(int p) { return new TaskOptions(p, retry, misfire); }
    public TaskOptions withRetry(RetryPolicy r) { return new TaskOptions(priority, r, misfire); }
    public TaskOptions withMisfire(MisfirePolicy m) { return new TaskOptions(priority, retry, m); }
}
