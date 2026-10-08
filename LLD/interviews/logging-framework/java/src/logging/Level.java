package logging;

/** Severity, lowest to highest. The declaration order IS the ordering: compare with ordinal(). */
public enum Level {
    TRACE, DEBUG, INFO, WARN, ERROR,
    /** Only used as a threshold: "log nothing". Never used for an event. */
    OFF;

    /** True if an event at this level passes a logger whose threshold is {@code threshold}. */
    public boolean isAtLeast(Level threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
