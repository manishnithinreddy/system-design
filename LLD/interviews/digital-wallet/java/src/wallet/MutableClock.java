package wallet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A Clock that tests can move forward by hand: "jump to tomorrow" without sleeping. */
final class MutableClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    MutableClock(Instant start, ZoneId zone) { this.now = start; this.zone = zone; }

    void advance(Duration d) { now = now.plus(d); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }
}
