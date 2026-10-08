package fs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Path text helpers. Pure string work: they never look at the tree. */
public final class Path {
    private Path() {}

    public static boolean isAbsolute(String raw) { return raw.startsWith("/"); }

    /** "/a//b/./c/" -> [a, b, ., c]. Empty parts (double or trailing slashes) are dropped; . and .. are kept. */
    public static List<String> split(String raw) {
        if (raw == null || raw.isEmpty()) throw new FsException.NoSuchFile("empty path");
        List<String> parts = new ArrayList<>();
        for (String p : raw.split("/")) if (!p.isEmpty()) parts.add(p);
        return parts;
    }

    /** A relative path is relative to the current directory; an absolute one ignores it. */
    public static String join(String cwd, String raw) {
        if (isAbsolute(raw)) return raw;
        return cwd.endsWith("/") ? cwd + raw : cwd + "/" + raw;
    }

    /**
     * LEXICAL normalisation: "/a/./b/../c" -> "/a/c", "/.." -> "/", "a/../../b" -> "../b".
     * It does not look at the tree, so it treats "x/.." as "nothing" even when x is a symlink.
     * The FileSystem's path walk handles ".." physically instead (see FileSystem.resolve).
     */
    public static String normalize(String raw) {
        Deque<String> out = new ArrayDeque<>();
        boolean absolute = isAbsolute(raw);
        for (String part : split(raw)) {
            if (part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty() && !out.peekLast().equals("..")) out.pollLast();
                else if (!absolute) out.addLast("..");   // a relative path may climb above its start
                // absolute and already at "/": ".." at the root stays at the root
            } else {
                out.addLast(part);
            }
        }
        String joined = String.join("/", out);
        if (absolute) return "/" + joined;
        return joined.isEmpty() ? "." : joined;
    }
}
