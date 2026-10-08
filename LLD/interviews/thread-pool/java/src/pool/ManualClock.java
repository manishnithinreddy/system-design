package pool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when told to: max-lifetime and leak tests become exact, with no sleeping. */
public final class ManualClock extends Clock {
    private volatile Instant now;

    public ManualClock(Instant start) { this.now = start; }

    public void advance(Duration d) { now = now.plus(d); }

    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
