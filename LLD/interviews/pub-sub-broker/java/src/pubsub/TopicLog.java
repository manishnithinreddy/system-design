package pubsub;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Kafka model, in miniature: messages are appended to a log and NOT deleted when read. Each record gets
 * an offset (its position: 0, 1, 2, ...). A consumer group just remembers the next offset it wants
 * (its committed offset), so it can re-read from any point, and a new group can start at the beginning.
 * Old records disappear only through retention (by count or age), never because someone consumed them.
 */
public final class TopicLog {
    public record Record(long offset, Message message) {}

    /** Where a group that has never committed starts reading. */
    public enum StartFrom { EARLIEST, LATEST }

    private final List<Record> records = new ArrayList<>();
    private final Map<String, Long> committed = new HashMap<>();
    private final int maxRecords;
    private final Duration maxAge;
    private final Clock clock;
    private long nextOffset = 0;

    TopicLog(int maxRecords, Duration maxAge, Clock clock) {
        this.maxRecords = maxRecords;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    synchronized long append(Message m) {
        records.add(new Record(nextOffset, m));
        enforceRetention();
        return nextOffset++;
    }

    /** Deletes records beyond the count limit or older than maxAge. Returns how many were deleted. */
    synchronized int enforceRetention() {
        int drop = Math.max(0, records.size() - maxRecords);
        var cutoff = clock.instant().minus(maxAge);
        while (drop < records.size() && records.get(drop).message().publishedAt().isBefore(cutoff)) drop++;
        records.subList(0, drop).clear();
        return drop;
    }

    public synchronized long earliestOffset() { return records.isEmpty() ? nextOffset : records.get(0).offset(); }
    public synchronized long endOffset() { return nextOffset; }

    /**
     * Up to {@code max} records from the group's committed offset. Does NOT move the offset: call commit()
     * after processing (at-least-once). Polling again without committing returns the same records.
     */
    public synchronized List<Record> poll(String group, StartFrom startIfNew, int max) {
        long from = position(group, startIfNew);
        int index = (int) (from - earliestOffset());
        return List.copyOf(records.subList(index, Math.min(records.size(), index + max)));
    }

    /** "I have processed everything before nextOffsetToRead." Also how you seek/replay: commit an older offset. */
    public synchronized void commit(String group, long nextOffsetToRead) {
        if (nextOffsetToRead < 0 || nextOffsetToRead > nextOffset) throw new IllegalArgumentException("offset out of range");
        committed.put(group, nextOffsetToRead);
    }

    /** Lag: how many records the group still has to read. The number you alert on. */
    public synchronized long lag(String group) {
        Long c = committed.get(group);
        return c == null ? 0 : nextOffset - Math.max(c, earliestOffset());
    }

    private long position(String group, StartFrom startIfNew) {
        Long c = committed.get(group);
        if (c == null) {
            c = startIfNew == StartFrom.EARLIEST ? earliestOffset() : nextOffset;
            committed.put(group, c);
        }
        return Math.max(c, earliestOffset());   // fell behind retention: those records are gone, skip ahead
    }
}
