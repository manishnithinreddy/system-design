package scheduler;

/** What to do when a run is found much later than planned (scheduler was down, busy, or a run overran). */
public enum MisfirePolicy {
    /** Run once now (all missed runs collapse into one); the schedule continues from now. */
    FIRE_ONCE_NOW,
    /** Do not run now; jump to the next planned time after now. A one-shot task is dropped. */
    SKIP
}
