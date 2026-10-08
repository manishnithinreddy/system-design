package logging;

/** SLF4J-style "{}" substitution. Called only when the level is enabled. */
public final class MessageFormatter {
    private MessageFormatter() {}

    /** Number of "{}" placeholders in the template. */
    public static int countPlaceholders(String template) {
        int n = 0;
        for (int i = template.indexOf("{}"); i >= 0; i = template.indexOf("{}", i + 2)) n++;
        return n;
    }

    /**
     * Replaces each "{}" with the next argument's String.valueOf.
     * Fewer args than placeholders: the extra "{}" stay as they are. More args: the extras are ignored.
     */
    public static String format(String template, Object[] args, int argCount) {
        if (argCount == 0) return template;
        StringBuilder sb = new StringBuilder(template.length() + 16 * argCount);
        int from = 0, used = 0;
        while (used < argCount) {
            int at = template.indexOf("{}", from);
            if (at < 0) break;
            sb.append(template, from, at).append(safeToString(args[used++]));
            from = at + 2;
        }
        return sb.append(template, from, template.length()).toString();
    }

    /** A broken toString() must never turn a log call into an exception in business code. */
    private static String safeToString(Object o) {
        try {
            return String.valueOf(o);
        } catch (RuntimeException e) {
            return "[toString() failed: " + e + "]";
        }
    }
}
