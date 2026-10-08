package logging;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class LoggingTests {
    private static int passed = 0;
    static final Instant T0 = Instant.parse("2026-10-08T03:30:00Z");

    public static void main(String[] args) throws Exception {
        levelThreshold();
        hierarchyInheritanceOverrideAndRuntimeChange();
        additivity();
        disabledLevelNeverFormatsArguments();
        placeholderEdgeCases();
        mdcSnapshotIsIsolatedPerThread();
        patternLayoutLine();
        jsonEscaping();
        redaction();
        rateLimitFilterChain();
        asyncBlockDeliversAllInOrder();
        asyncDropCountsDrops();
        asyncDiscardsLowLevelsWhenNearlyFull();
        asyncCloseDrainsQueue();
        brokenAppenderNeverThrowsToCaller();
        System.out.println("All " + passed + " tests passed.");
    }

    record Setup(LoggerContext ctx, ListAppender list, ManualClock clock) {}

    static Setup setup() {
        var clock = new ManualClock(T0);
        var ctx = new LoggerContext(clock);
        var list = new ListAppender();
        ctx.root().addAppender(list);
        return new Setup(ctx, list, clock);
    }

    static void levelThreshold() {
        var s = setup();
        Logger log = s.ctx.getLogger("com.shop.Orders");
        log.trace("t"); log.debug("d"); log.info("i"); log.warn("w"); log.error("e");
        assertEquals(List.of("i", "w", "e"), s.list.messages(), "root default INFO lets INFO and above through");
        s.ctx.root().setLevel(Level.OFF);
        log.error("nothing");
        assertEquals(3, s.list.events().size(), "OFF silences everything");
        pass("levelThreshold");
    }

    static void hierarchyInheritanceOverrideAndRuntimeChange() {
        var s = setup();
        Logger db = s.ctx.getLogger("com.shop.db.Pool");
        assertEquals("com.shop.db", db.parent().name(), "missing parents are created");
        assertEquals(Level.INFO, db.effectiveLevel(), "inherits ROOT's INFO");
        s.ctx.setLevel("com.shop", Level.WARN);
        assertEquals(Level.WARN, db.effectiveLevel(), "nearest ancestor with a level wins");
        s.ctx.setLevel("com.shop.db", Level.DEBUG);
        db.debug("query took {} ms", 12);
        assertEquals(List.of("query took 12 ms"), s.list.messages(), "more specific override, changed at runtime");
        s.ctx.setLevel("com.shop.db", null);
        db.debug("hidden again");
        assertEquals(1, s.list.events().size(), "null = inherit again (WARN from com.shop)");
        assertSame(s.ctx.getLogger("com.shop.db.Pool"), db, "registry returns the same instance");
        assertSame(LoggerFactory.getLogger(LoggingTests.class), LoggerFactory.getLogger("logging.LoggingTests"),
                "factory: class name = logger name, one instance per name");
        pass("hierarchyInheritanceOverrideAndRuntimeChange");
    }

    static void additivity() {
        var s = setup();
        var audit = new ListAppender();
        Logger pay = s.ctx.getLogger("com.shop.payment");
        pay.addAppender(audit);
        pay.info("charged");
        assertEquals(List.of("charged"), audit.messages(), "own appender");
        assertEquals(List.of("charged"), s.list.messages(), "additive: ROOT's appender too");
        pay.setAdditive(false);
        s.ctx.getLogger("com.shop.payment.Stripe").info("refund");
        assertEquals(List.of("charged", "refund"), audit.messages(), "child reaches payment's appender");
        assertEquals(List.of("charged"), s.list.messages(), "but stops there: ROOT doesn't get it");
        pass("additivity");
    }

    static void disabledLevelNeverFormatsArguments() {
        var s = setup();
        AtomicInteger calls = new AtomicInteger();
        Object expensive = new Object() {
            @Override public String toString() { calls.incrementAndGet(); return "big-dump"; }
        };
        Logger log = s.ctx.getLogger("com.shop");
        log.debug("state: {}", expensive);
        assertEquals(0, calls.get(), "DEBUG disabled: toString() never called");
        log.info("state: {}", expensive);
        assertEquals(1, calls.get(), "INFO enabled: formatted exactly once");
        pass("disabledLevelNeverFormatsArguments");
    }

    static void placeholderEdgeCases() {
        var s = setup();
        Logger log = s.ctx.getLogger("x");
        var boom = new IllegalStateException("boom");
        log.info("a={} b={}", 1);                     // fewer args than {}
        log.info("a={}", 1, 2);                       // extra args ignored
        log.info("failed for {}", "alice", boom);     // Throwable last, no {} left for it -> exception
        log.info("cause: {}", boom);                  // Throwable consumed by {} -> just text
        log.info("no args {}");
        log.info("{}{}", null, "x");
        assertEquals(List.of("a=1 b={}", "a=1", "failed for alice", "cause: java.lang.IllegalStateException: boom",
                "no args {}", "nullx"), s.list.messages(), "substitution");
        assertSame(boom, s.list.events().get(2).throwable(), "Throwable extracted as the event's exception");
        assertEquals(null, s.list.events().get(3).throwable(), "consumed Throwable is not the exception");
        pass("placeholderEdgeCases");
    }

    static void mdcSnapshotIsIsolatedPerThread() throws Exception {
        var s = setup();
        Logger log = s.ctx.getLogger("web");
        MDC.put("requestId", "r-1");
        log.info("main");
        MDC.put("requestId", "r-changed");                         // must not change the event above
        Thread other = new Thread(() -> { MDC.put("requestId", "r-2"); log.info("other"); MDC.clear(); });
        other.start(); other.join();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> log.info("pool, not wrapped")).get();
        pool.submit(MDC.wrap(() -> log.info("pool, wrapped"))).get();
        pool.shutdown();
        MDC.clear();
        List<String> ids = s.list.events().stream().map(e -> String.valueOf(e.mdc().get("requestId"))).toList();
        assertEquals(List.of("r-1", "r-2", "null", "r-changed"), ids, "snapshot per event, per thread; wrap() propagates");
        pass("mdcSnapshotIsIsolatedPerThread");
    }

    static void patternLayoutLine() {
        var s = setup();
        var buf = new ByteArrayOutputStream();
        s.ctx.root().addAppender(new ConsoleAppender(new PrintStream(buf, true, StandardCharsets.UTF_8), new PatternLayout()));
        MDC.put("requestId", "r-9");
        s.ctx.getLogger("com.shop.Orders").warn("stock low: {}", 2);
        MDC.clear();
        String line = buf.toString(StandardCharsets.UTF_8).strip();
        assertEquals("2026-10-08T03:30:00.000Z WARN  [main] com.shop.Orders - stock low: 2 {requestId=r-9}", line, "pattern");
        pass("patternLayoutLine");
    }

    static void jsonEscaping() {
        var e = new LogEvent(T0, Level.INFO, "a", "t", "say \"hi\"\\ C:\\tmp\nline2\ttab\u0001end",
                "main", Map.of("user", "o\"brien"), null);
        String json = new JsonLayout().format(e);
        assertEquals("{\"ts\":\"2026-10-08T03:30:00.000Z\",\"level\":\"INFO\",\"logger\":\"a\",\"thread\":\"main\","
                + "\"msg\":\"say \\\"hi\\\"\\\\ C:\\\\tmp\\nline2\\ttab\\u0001end\",\"user\":\"o\\\"brien\"}", json, "escaped");
        assertEquals(false, json.contains("\n"), "one event = one physical line");
        pass("jsonEscaping");
    }

    static void redaction() {
        var e = new LogEvent(T0, Level.INFO, "auth", "t", "login user=bob password=hunter2, apiKey: abc123 ok",
                "main", Map.of("token", "eyJhbGci"), null);
        String text = new RedactingLayout(new PatternLayout()).format(e);
        assertEquals(true, text.contains("password=***, apiKey: *** ok"), "key=value masked: " + text);
        assertEquals(true, text.contains("token=***"), "MDC value masked: " + text);
        String json = new RedactingLayout(new JsonLayout()).format(e);
        assertEquals(true, json.contains("\"token\":\"***\""), "JSON field masked: " + json);
        assertEquals(false, json.contains("hunter2") || json.contains("eyJ") || json.contains("abc123"), "no secret left");
        pass("redaction");
    }

    static void rateLimitFilterChain() {
        var s = setup();
        var limiter = new RateLimitFilter(2, 1_000, s.clock);
        s.ctx.addFilter(Filter.acceptAtOrAbove(Level.ERROR));     // first link: errors are never limited
        s.ctx.addFilter(limiter);
        Logger log = s.ctx.getLogger("db");
        for (int i = 0; i < 5; i++) log.warn("timeout for user {}", i);
        for (int i = 0; i < 3; i++) log.error("down {}", i);
        log.warn("other call site");
        s.clock.advanceMillis(1_000);
        log.warn("timeout for user {}", 99);
        assertEquals(List.of("timeout for user 0", "timeout for user 1", "down 0", "down 1", "down 2",
                "other call site", "timeout for user 99"), s.list.messages(), "2 per window per template");
        assertEquals(3L, limiter.suppressed(), "suppressed count");
        pass("rateLimitFilterChain");
    }

    static void asyncBlockDeliversAllInOrder() {
        var s = setup();
        var sink = new ListAppender();
        var async = new AsyncAppender(sink, 16, AsyncAppender.OverflowPolicy.BLOCK);
        Logger log = s.ctx.getLogger("bulk");
        log.setAdditive(false);
        log.addAppender(async);
        for (int i = 0; i < 2_000; i++) log.info("{}", i);
        async.close();
        List<String> expected = IntStream.range(0, 2_000).mapToObj(String::valueOf).toList();
        assertEquals(expected, sink.messages(), "all events, in order, through a 16-slot queue");
        assertEquals(0L, async.dropped(), "BLOCK never drops");
        pass("asyncBlockDeliversAllInOrder");
    }

    /** An appender that blocks until released: makes queue-full situations deterministic. */
    static final class GatedAppender implements Appender {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ListAppender inner = new ListAppender();
        @Override public void append(LogEvent e) {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            inner.append(e);
        }
    }

    static Logger asyncLogger(Setup s, AsyncAppender async) {
        Logger log = s.ctx.getLogger("async");
        log.setAdditive(false);
        log.setLevel(Level.TRACE);
        log.addAppender(async);
        return log;
    }

    static void asyncDropCountsDrops() throws Exception {
        var s = setup();
        var gated = new GatedAppender();
        var async = new AsyncAppender(gated, 2, AsyncAppender.OverflowPolicy.DROP);
        Logger log = asyncLogger(s, async);
        log.info("e0");
        assertEquals(true, gated.entered.await(1, TimeUnit.SECONDS), "worker took e0 and is stuck in the slow appender");
        for (int i = 1; i <= 5; i++) log.info("e{}", i);          // e1, e2 fill the queue; e3..e5 dropped
        assertEquals(3L, async.dropped(), "dropped while full");
        gated.release.countDown();
        async.close();
        assertEquals(List.of("e0", "e1", "e2"), gated.inner.messages(), "the queued ones still arrive");
        pass("asyncDropCountsDrops");
    }

    static void asyncDiscardsLowLevelsWhenNearlyFull() throws Exception {
        var s = setup();
        var gated = new GatedAppender();
        var async = new AsyncAppender(gated, 10, AsyncAppender.OverflowPolicy.DISCARD_BELOW_WARN_WHEN_80_PERCENT_FULL);
        Logger log = asyncLogger(s, async);
        log.info("first");
        gated.entered.await(1, TimeUnit.SECONDS);
        for (int i = 0; i < 8; i++) log.debug("d{}", i);           // queue now 8/10 = 80%
        log.debug("dropped debug");
        log.info("dropped info");
        log.warn("kept warn");
        log.error("kept error");
        assertEquals(2L, async.dropped(), "only the low-level events were discarded");
        gated.release.countDown();
        async.close();
        List<String> got = gated.inner.messages();
        assertEquals(List.of("kept warn", "kept error"), got.subList(got.size() - 2, got.size()), "WARN/ERROR kept");
        assertEquals(11, got.size(), "first + 8 debug + warn + error");
        pass("asyncDiscardsLowLevelsWhenNearlyFull");
    }

    static void asyncCloseDrainsQueue() throws Exception {
        var s = setup();
        var gated = new GatedAppender();
        var async = new AsyncAppender(gated, 100, AsyncAppender.OverflowPolicy.BLOCK);
        Logger log = asyncLogger(s, async);
        for (int i = 0; i < 50; i++) log.info("m{}", i);
        gated.entered.await(1, TimeUnit.SECONDS);
        gated.release.countDown();
        async.close();                                             // returns only after the queue is empty
        assertEquals(50, gated.inner.messages().size(), "nothing lost on close");
        assertEquals(0, async.pending(), "queue empty");
        log.info("after close");
        assertEquals(1L, async.dropped(), "events after close are counted, not silently lost");
        pass("asyncCloseDrainsQueue");
    }

    static void brokenAppenderNeverThrowsToCaller() {
        var s = setup();
        s.ctx.root().addAppender(e -> { throw new IllegalStateException("disk full"); });
        var after = new ListAppender();
        s.ctx.root().addAppender(after);
        PrintStream realErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));   // keep test output clean
        try {
            s.ctx.getLogger("x").info("still works");
        } finally {
            System.setErr(realErr);
        }
        assertEquals(List.of("still works"), after.messages(), "other appenders still get the event");
        pass("brokenAppenderNeverThrowsToCaller");
    }

    // ---------------- tiny assert helpers ----------------

    private static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertSame(Object expected, Object actual, String what) {
        if (expected != actual) throw new AssertionError(what + ": not the same instance");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
