package logging;

import java.util.regex.Pattern;

/**
 * Decorator around any layout: masks values of sensitive keys in the final text,
 * e.g. "password=hunter2" -> "password=***", "\"token\":\"abc\"" -> "\"token\":\"***\"".
 * A safety net, not a guarantee: the real fix is not logging secrets in the first place.
 */
public final class RedactingLayout implements Layout {
    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key)(\\s*[=:]\\s*)([^\\s,&;\"}]+)");
    private static final Pattern JSON_FIELD =
            Pattern.compile("(?i)(\"(?:password|passwd|secret|token|api[_-]?key)\":\")((?:[^\"\\\\]|\\\\.)*)(\")");

    private final Layout inner;

    public RedactingLayout(Layout inner) { this.inner = inner; }

    @Override
    public String format(LogEvent event) {
        String text = inner.format(event);
        text = JSON_FIELD.matcher(text).replaceAll("$1***$3");
        return KEY_VALUE.matcher(text).replaceAll("$1$2***");
    }
}
