package logging;

import java.util.ArrayList;
import java.util.List;

/** Keeps events in memory. For tests: "did my code log X?" */
public final class ListAppender implements Appender {
    private final List<LogEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(LogEvent event) { events.add(event); }

    public synchronized List<LogEvent> events() { return List.copyOf(events); }

    public synchronized List<String> messages() {
        return events.stream().map(LogEvent::message).toList();
    }
}
