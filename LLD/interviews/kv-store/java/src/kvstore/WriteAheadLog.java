package kvstore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Append-only log of committed changes, one record per line:
 *   S<TAB>key<TAB>value<TAB>expiresAt     (set)
 *   D<TAB>key                             (delete)
 *   B ... C                               (a transaction: applied only if the closing C is present)
 * Keys/values are escaped so tabs/newlines can't break the format.
 *
 * Crash safety: a crash can leave a half-written last line or a B without its C.
 * Replay ignores both, so a transaction is either fully recovered or not at all.
 */
public final class WriteAheadLog implements AutoCloseable {
    private final Path path;
    private final FsyncPolicy policy;
    private final Clock clock;
    private FileChannel channel;
    private long lastFsyncMillis;

    public WriteAheadLog(Path path, FsyncPolicy policy, Clock clock) {
        this.path = path;
        this.policy = policy;
        this.clock = clock;
        open();
    }

    private void open() {
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String set(String key, Entry e) {
        return "S\t" + esc(key) + "\t" + esc(e.value()) + "\t" + e.expiresAtMillis();
    }

    static String delete(String key) {
        return "D\t" + esc(key);
    }

    /** Append records as ONE write (a batch is framed with B/C) and fsync per policy. */
    void append(List<String> records, boolean asTransaction) {
        StringBuilder sb = new StringBuilder();
        if (asTransaction) sb.append("B\n");
        for (String r : records) sb.append(r).append('\n');
        if (asTransaction) sb.append("C\n");
        try {
            ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) channel.write(buf);
            long now = clock.millis();
            if (policy == FsyncPolicy.ALWAYS || (policy == FsyncPolicy.EVERY_SECOND && now - lastFsyncMillis >= 1000)) {
                channel.force(false);       // fsync: until this returns, the data may only be in the OS page cache
                lastFsyncMillis = now;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replays the log, calling setter/deleter for every committed change, in order. */
    void replay(java.util.function.BiConsumer<String, Entry> setter, Consumer<String> deleter) {
        if (!Files.exists(path)) return;
        List<String[]> batch = null;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("B")) { batch = new ArrayList<>(); continue; }
                if (line.equals("C")) {
                    if (batch != null) for (String[] rec : batch) apply(rec, setter, deleter);
                    batch = null;
                    continue;
                }
                String[] rec = parse(line);
                if (rec == null) break;                         // torn/garbage tail: stop here
                if (batch != null) batch.add(rec); else apply(rec, setter, deleter);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // an open batch (B without C) at the end = crashed mid-commit: ignored
    }

    /** Compaction: replace the log with one record per live key (write temp, fsync, atomic rename). */
    void rewrite(Map<String, Entry> live) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            StringBuilder sb = new StringBuilder();
            live.forEach((k, e) -> sb.append(set(k, e)).append('\n'));
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) out.write(buf);
                out.force(true);
            }
            channel.close();
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void apply(String[] rec, java.util.function.BiConsumer<String, Entry> setter, Consumer<String> deleter) {
        if (rec[0].equals("S")) setter.accept(rec[1], new Entry(rec[2], Long.parseLong(rec[3])));
        else deleter.accept(rec[1]);
    }

    private static String[] parse(String line) {
        String[] p = line.split("\t", -1);
        try {
            if (p[0].equals("S") && p.length == 4) {
                Long.parseLong(p[3]);
                return new String[]{"S", unesc(p[1]), unesc(p[2]), p[3]};
            }
            if (p[0].equals("D") && p.length == 2) return new String[]{"D", unesc(p[1])};
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        try {
            channel.force(true);
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
