package scheduler;

/**
 * Where the scheduler gets "now" from. Injected so tests can move time by hand instead of sleeping.
 * (Like java.time.Clock, but one method is all we need.)
 */
@FunctionalInterface
public interface TimeSource {
    long nowMillis();

    /** Real wall-clock time (epoch millis). See L6 for why a monotonic clock matters for waiting. */
    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
