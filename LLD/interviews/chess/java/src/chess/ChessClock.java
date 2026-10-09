package chess;

import java.util.function.LongSupplier;

/** Two-player chess clock with increment. Time comes from an injected source so tests need no sleeping. */
public final class ChessClock {
    private final long[] left = new long[2];
    private final long incrementMs;
    private final LongSupplier nowMs;
    private Color running;
    private long since;

    public ChessClock(long initialMs, long incrementMs, LongSupplier nowMs) {
        left[0] = initialMs; left[1] = initialMs;
        this.incrementMs = incrementMs; this.nowMs = nowMs;
    }

    public void start(Color first) { running = first; since = nowMs.getAsLong(); }

    /** The player on the move has moved: charge their thinking time, add the increment, start the opponent's clock. */
    public void press() {
        long t = nowMs.getAsLong();
        left[running.ordinal()] += incrementMs - (t - since);
        running = running.opposite();
        since = t;
    }

    public long remainingMs(Color c) {
        return c == running ? left[c.ordinal()] - (nowMs.getAsLong() - since) : left[c.ordinal()];
    }

    /** The colour whose time ran out ("flag fell"), or null. */
    public Color flagged() {
        for (Color c : Color.values()) if (remainingMs(c) <= 0) return c;
        return null;
    }
}
