package kvstore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class KeyValueStoreTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        basicCommands();
        countIsAlwaysExact();
        nestedTransactions();
        rollbackWithoutTransaction();
        ttlLazyAndActiveExpiry();
        rollbackRestoresTtl();
        logReplaysCommittedState();
        uncommittedTransactionIsLostAfterCrash();
        tornTailIsIgnored();
        compactionKeepsStateAndShrinksLog();
        matchesNaiveCopyModel();
        System.out.println("All " + passed + " tests passed.");
    }

    static List<String> run(CommandProcessor cli, String... lines) {
        return java.util.Arrays.stream(lines).map(cli::execute).toList();
    }

    static void basicCommands() {
        var cli = new CommandProcessor(new KeyValueStore(new MutableClock()));
        assertEquals(List.of("OK", "10", "NULL", "1", "NULL", "0"),
                run(cli, "SET a 10", "GET a", "GET b", "DELETE a", "GET a", "DELETE a"), "set/get/delete");
        pass("basicCommands");
    }

    static void countIsAlwaysExact() {
        var cli = new CommandProcessor(new KeyValueStore(new MutableClock()));
        assertEquals(List.of("OK", "OK", "2", "OK", "1", "1", "0"),
                run(cli, "SET a 10", "SET b 10", "COUNT 10", "SET b 20", "COUNT 10", "DELETE a", "COUNT 10"), "count");
        pass("countIsAlwaysExact");
    }

    /** The classic interview sequence. */
    static void nestedTransactions() {
        var cli = new CommandProcessor(new KeyValueStore(new MutableClock()));
        assertEquals(List.of(
                "OK", "OK", "10",              // layer 1: a = 10
                "OK", "OK", "20",              // layer 2: a = 20
                "OK", "OK", "30",              // layer 3: a = 30
                "OK", "20",                    // ROLLBACK layer 3 -> 20
                "OK", "20",                    // COMMIT layer 2 into layer 1 -> still 20
                "OK", "20",                    // COMMIT layer 1 -> permanent
                "NO TRANSACTION"),             // nothing left to commit
            run(cli,
                "BEGIN", "SET a 10", "GET a",
                "BEGIN", "SET a 20", "GET a",
                "BEGIN", "SET a 30", "GET a",
                "ROLLBACK", "GET a",
                "COMMIT", "GET a",
                "COMMIT", "GET a",
                "COMMIT"), "nested");
        pass("nestedTransactions");
    }

    static void rollbackWithoutTransaction() {
        KeyValueStore s = new KeyValueStore(new MutableClock());
        s.begin(); s.set("a", "1"); s.begin(); s.set("a", "2"); s.commit();   // nested commit -> merged into outer
        s.rollback();                                                          // outer rollback undoes BOTH
        assertEquals(java.util.Optional.empty(), s.get("a"), "outer rollback undoes committed nested work");
        var cli = new CommandProcessor(s);
        assertEquals(List.of("NO TRANSACTION", "NO TRANSACTION"), run(cli, "ROLLBACK", "COMMIT"), "no txn");
        pass("rollbackWithoutTransaction");
    }

    static void ttlLazyAndActiveExpiry() {
        MutableClock clock = new MutableClock();
        KeyValueStore s = new KeyValueStore(clock);
        s.set("session", "alice");
        s.set("other", "alice");
        s.expire("session", 30);
        assertEquals(30L, s.ttl("session"), "ttl 30");
        assertEquals(-1L, s.ttl("other"), "no ttl");
        assertEquals(2, s.count("alice"), "both counted");
        clock.advance(Duration.ofSeconds(30));
        assertEquals(1, s.count("alice"), "expired key no longer counted (active expiry)");
        assertEquals(-2L, s.ttl("session"), "gone");
        s.set("other", "bob"); s.expire("other", 5); s.set("other", "carol");   // SET clears the TTL
        clock.advance(Duration.ofSeconds(10));
        assertEquals(java.util.Optional.of("carol"), s.get("other"), "stale heap entry ignored");
        pass("ttlLazyAndActiveExpiry");
    }

    static void rollbackRestoresTtl() {
        MutableClock clock = new MutableClock();
        KeyValueStore s = new KeyValueStore(clock);
        s.set("k", "v");
        s.expire("k", 100);
        s.begin();
        s.set("k", "v2");                 // clears TTL inside the transaction
        assertEquals(-1L, s.ttl("k"), "no ttl in txn");
        s.rollback();
        assertEquals(100L, s.ttl("k"), "ttl restored");
        pass("rollbackRestoresTtl");
    }

    static Path tempLog() throws IOException {
        Path dir = Files.createTempDirectory("kvtest");
        return dir.resolve("appendonly.log");
    }

    static void logReplaysCommittedState() throws IOException {
        Path file = tempLog();
        MutableClock clock = new MutableClock();
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock)) {
            KeyValueStore s = new KeyValueStore(clock, log);
            s.set("a", "1");
            s.set("tab\tkey", "multi\nline");
            s.begin(); s.set("b", "2"); s.delete("a"); s.commit();
            s.begin(); s.set("c", "3"); s.rollback();                 // never written
        }
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock)) {   // "restart"
            KeyValueStore s = new KeyValueStore(clock, log);
            assertEquals(java.util.Optional.empty(), s.get("a"), "a deleted");
            assertEquals(java.util.Optional.of("2"), s.get("b"), "b recovered");
            assertEquals(java.util.Optional.empty(), s.get("c"), "rolled back c not recovered");
            assertEquals(java.util.Optional.of("multi\nline"), s.get("tab\tkey"), "escaping round-trips");
        }
        pass("logReplaysCommittedState");
    }

    static void uncommittedTransactionIsLostAfterCrash() throws IOException {
        Path file = tempLog();
        MutableClock clock = new MutableClock();
        WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock);
        KeyValueStore s = new KeyValueStore(clock, log);
        s.set("committed", "yes");
        s.begin(); s.set("pending", "maybe");     // process "crashes" here: never committed
        log.close();
        KeyValueStore recovered = new KeyValueStore(clock, new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock));
        assertEquals(java.util.Optional.of("yes"), recovered.get("committed"), "committed survives");
        assertEquals(java.util.Optional.empty(), recovered.get("pending"), "uncommitted gone");
        pass("uncommittedTransactionIsLostAfterCrash");
    }

    static void tornTailIsIgnored() throws IOException {
        Path file = tempLog();
        MutableClock clock = new MutableClock();
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock)) {
            KeyValueStore s = new KeyValueStore(clock, log);
            s.set("a", "1");
        }
        // simulate a crash in the middle of writing a transaction: B + one record, no C, half a line
        Files.writeString(file, "B\nS\tb\t2\t" + Long.MAX_VALUE + "\nS\tc\t3\t92233", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        KeyValueStore s = new KeyValueStore(clock, new WriteAheadLog(file, FsyncPolicy.ALWAYS, clock));
        assertEquals(java.util.Optional.of("1"), s.get("a"), "earlier data intact");
        assertEquals(java.util.Optional.empty(), s.get("b"), "half-written transaction not applied");
        pass("tornTailIsIgnored");
    }

    static void compactionKeepsStateAndShrinksLog() throws IOException {
        Path file = tempLog();
        MutableClock clock = new MutableClock();
        try (WriteAheadLog log = new WriteAheadLog(file, FsyncPolicy.NEVER, clock)) {
            KeyValueStore s = new KeyValueStore(clock, log);
            for (int i = 0; i < 1000; i++) s.set("counter", String.valueOf(i));
            s.set("x", "y");
            long before = Files.size(file);
            s.compact();
            long after = Files.size(file);
            if (after >= before / 10) throw new AssertionError("log not compacted: " + before + " -> " + after);
        }
        KeyValueStore s = new KeyValueStore(clock, new WriteAheadLog(file, FsyncPolicy.NEVER, clock));
        assertEquals(java.util.Optional.of("999"), s.get("counter"), "state after compaction");
        assertEquals(2, s.size(), "two keys");
        pass("compactionKeepsStateAndShrinksLog");
    }

    /** Property test: 20,000 random ops vs a naive model that copies the whole map on BEGIN. */
    static void matchesNaiveCopyModel() {
        Random rnd = new Random(1);
        KeyValueStore store = new KeyValueStore(new MutableClock());
        Map<String, String> model = new HashMap<>();
        Deque<Map<String, String>> snapshots = new ArrayDeque<>();
        for (int i = 0; i < 20_000; i++) {
            String k = "k" + rnd.nextInt(8), v = "v" + rnd.nextInt(4);
            switch (rnd.nextInt(7)) {
                case 0, 1 -> { store.set(k, v); model.put(k, v); }
                case 2 -> assertEquals(store.delete(k), model.remove(k) != null, "delete " + i);
                case 3 -> { store.begin(); snapshots.push(new HashMap<>(model)); }
                case 4 -> {
                    if (snapshots.isEmpty()) continue;
                    store.rollback(); model = snapshots.pop();
                }
                case 5 -> {
                    if (snapshots.isEmpty()) continue;
                    store.commit(); snapshots.pop();
                }
                default -> {
                    assertEquals(java.util.Optional.ofNullable(model.get(k)), store.get(k), "get " + i);
                    long expected = model.values().stream().filter(v::equals).count();
                    assertEquals((int) expected, store.count(v), "count " + i);
                }
            }
        }
        pass("matchesNaiveCopyModel");
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
