package booking;

import java.time.Duration;
import java.time.Instant;

/** A half-open time interval [start, end): includes start, excludes end. */
public record TimeSlot(Instant start, Instant end) {
    public TimeSlot {
        if (!start.isBefore(end)) throw new IllegalArgumentException("start must be before end: " + start + " .. " + end);
    }

    /** Two half-open intervals overlap exactly when each starts before the other ends. */
    public boolean overlaps(TimeSlot o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }

    public boolean contains(TimeSlot o) {
        return !o.start.isBefore(start) && !o.end.isAfter(end);
    }

    public Duration length() {
        return Duration.between(start, end);
    }
}
