package logging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when told to: deterministic timestamps and rate-limit windows in tests. */
public final class ManualClock extends Clock {
    private volatile Instant now;

    public ManualClock(Instant start) { this.now = start; }

    public void advanceMillis(long ms) { now = now.plusMillis(ms); }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
