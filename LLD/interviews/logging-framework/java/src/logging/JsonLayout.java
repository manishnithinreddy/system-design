package logging;

import java.util.TreeMap;

/** One JSON object per line ("JSON lines"), the format log pipelines parse without regexes. */
public final class JsonLayout implements Layout {
    @Override
    public String format(LogEvent e) {
        StringBuilder sb = new StringBuilder(192).append('{');
        field(sb, "ts", PatternLayout.TS.format(e.instant())).append(',');
        field(sb, "level", e.level().name()).append(',');
        field(sb, "logger", e.loggerName()).append(',');
        field(sb, "thread", e.threadName()).append(',');
        field(sb, "msg", e.message());
        for (var kv : new TreeMap<>(e.mdc()).entrySet()) field(sb.append(','), kv.getKey(), kv.getValue());
        if (e.throwable() != null) {
            field(sb.append(','), "error", e.throwable().toString()).append(',');
            field(sb, "stack", PatternLayout.stackTrace(e.throwable()));
        }
        return sb.append('}').toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        return escape(sb.append('"'), key).append("\":\"").append(escape(new StringBuilder(), value)).append('"');
    }

    /** JSON string escaping (RFC 8259): quote, backslash and every control character below 0x20. */
    static StringBuilder escape(StringBuilder sb, String s) {
        if (s == null) return sb.append("null");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb;
    }
}
