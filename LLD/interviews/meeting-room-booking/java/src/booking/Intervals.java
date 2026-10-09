package booking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Interval helpers: merge overlapping/touching intervals and find the gaps between them. */
public final class Intervals {
    private Intervals() {}

    /** Sort by start, then sweep left to right, extending the current block while the next one starts inside or at its end. */
    public static List<TimeSlot> merge(List<TimeSlot> in) {
        List<TimeSlot> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparing(TimeSlot::start));
        List<TimeSlot> out = new ArrayList<>();
        for (TimeSlot s : sorted) {
            if (!out.isEmpty() && !s.start().isAfter(out.get(out.size() - 1).end())) {
                TimeSlot last = out.remove(out.size() - 1);
                out.add(new TimeSlot(last.start(), s.end().isAfter(last.end()) ? s.end() : last.end()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** Free gaps inside window, given busy intervals (any order, may overlap). */
    public static List<TimeSlot> gaps(TimeSlot window, List<TimeSlot> busy) {
        List<TimeSlot> out = new ArrayList<>();
        var cursor = window.start();
        for (TimeSlot b : merge(busy)) {
            if (!b.end().isAfter(window.start()) || !b.start().isBefore(window.end())) continue;
            if (b.start().isAfter(cursor)) out.add(new TimeSlot(cursor, b.start()));
            if (b.end().isAfter(cursor)) cursor = b.end();
        }
        if (cursor.isBefore(window.end())) out.add(new TimeSlot(cursor, window.end()));
        return out;
    }
}
