package logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/** A fixed human-readable pattern: time LEVEL [thread] logger - message {mdc}, then the stack trace. */
public final class PatternLayout implements Layout {
    /** Always 3 fractional digits, always UTC: lines from different pods sort and compare correctly. */
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String format(LogEvent e) {
        StringBuilder sb = new StringBuilder(128)
                .append(TS.format(e.instant())).append(' ')
                .append(String.format("%-5s", e.level())).append(" [")
                .append(e.threadName()).append("] ")
                .append(e.loggerName()).append(" - ")
                .append(e.message());
        if (!e.mdc().isEmpty()) sb.append(' ').append(new TreeMap<>(e.mdc()));   // sorted: stable output
        if (e.throwable() != null) sb.append(System.lineSeparator()).append(stackTrace(e.throwable()).stripTrailing());
        return sb.toString();
    }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
