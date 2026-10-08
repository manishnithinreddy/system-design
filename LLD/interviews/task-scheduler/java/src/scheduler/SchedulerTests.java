package scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class SchedulerTests {
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        runsInTimeOrder();
        equalTimesRunFifo();
        priorityWinsAmongDueTasks();
        cancelIsLazy();
        fixedRateVsFixedDelay();
        retryWithBackoffThenDeadLetter();
        recurringTaskSurvivesDeadLetter();
        cronNextFireTimes();
        cronDaylightSavingGapAndOverlap();
        misfireFireOnceNow();
        misfireSkip();
        earlierTaskWakesBackgroundDispatcher();
        slowRecurringTaskNeverOverlaps();
        gracefulShutdown();
        System.out.println("All " + passed + " tests passed.");
    }

    static Scheduler newScheduler(ManualTimeSource clock) {
        return new Scheduler(clock, 1_000, () -> 1.0);   // jitter fixed at the cap: deterministic
    }

    static void runsInTimeOrder() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        List<String> ran = new ArrayList<>();
        s.schedule("c", () -> ran.add("c"), 300);
        s.schedule("a", () -> ran.add("a"), 100);
        s.schedule("b", () -> ran.add("b"), 200);
        clock.set(150);
        assertEquals(1, s.runDue(), "only a is due at 150");
        clock.set(1_000);
        s.runDue();
        assertEquals(List.of("a", "b", "c"), ran, "time order");
        pass("runsInTimeOrder");
    }

    static void equalTimesRunFifo() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        List<Integer> ran = new ArrayList<>();
        for (int i = 0; i < 20; i++) { int n = i; s.schedule("t" + n, () -> ran.add(n), 500); }
        clock.set(500);
        s.runDue();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) expected.add(i);
        assertEquals(expected, ran, "same time -> insertion order (sequence-number tie-break)");
        pass("equalTimesRunFifo");
    }

    static void priorityWinsAmongDueTasks() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        List<String> ran = new ArrayList<>();
        s.schedule("report", () -> ran.add("report"), new Schedule.Once(100), TaskOptions.DEFAULT);
        s.schedule("page-oncall", () -> ran.add("page-oncall"), new Schedule.Once(200), TaskOptions.DEFAULT.withPriority(10));
        s.schedule("cleanup", () -> ran.add("cleanup"), new Schedule.Once(100), TaskOptions.DEFAULT.withPriority(-1));
        clock.set(300);                                    // scheduler was busy: all three are due now
        s.runDue();
        assertEquals(List.of("page-oncall", "report", "cleanup"), ran, "priority first, then time");
        pass("priorityWinsAmongDueTasks");
    }

    static void cancelIsLazy() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask t = s.scheduleAtFixedRate("poll", runs::incrementAndGet, 100, 100);
        clock.set(100);
        s.runDue();
        assertEquals(true, t.cancel(), "cancel works");
        assertEquals(false, t.cancel(), "second cancel is a no-op");
        clock.set(10_000);
        assertEquals(0, s.runDue(), "cancelled task is dropped when it reaches the top of the heap");
        assertEquals(1, runs.get(), "ran once before cancel");
        assertEquals(ScheduledTask.State.CANCELLED, t.state(), "state");
        pass("cancelIsLazy");
    }

    /** Each run "takes" 3 s (the task moves the fake clock). Period/delay = 10 s. */
    static void fixedRateVsFixedDelay() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        ScheduledTask rate = s.scheduleAtFixedRate("rate", () -> clock.advance(3_000), 10_000, 10_000);
        clock.set(10_000);
        s.runDue();
        assertEquals(20_000L, rate.runAt(), "fixed rate: start-to-start, 10 s after the PLANNED start");
        ScheduledTask delay = s.scheduleWithFixedDelay("delay", () -> clock.advance(3_000), 0, 10_000);
        long started = clock.nowMillis();
        s.runDue();
        assertEquals(started + 3_000 + 10_000, delay.runAt(), "fixed delay: 10 s after the run FINISHED");
        pass("fixedRateVsFixedDelay");
    }

    static void retryWithBackoffThenDeadLetter() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        AtomicInteger attempts = new AtomicInteger();
        var opts = TaskOptions.DEFAULT.withRetry(new RetryPolicy(4, 1_000, 60_000));
        ScheduledTask t = s.schedule("webhook", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("HTTP 503");
        }, new Schedule.Once(0), opts);
        List<Long> retryTimes = new ArrayList<>();
        s.runDue();                                                   // attempt 1 at t=0
        for (int i = 0; i < 3; i++) {
            if (t.state() == ScheduledTask.State.DEAD) break;
            retryTimes.add(t.runAt());
            clock.set(t.runAt());
            s.runDue();
        }
        assertEquals(List.of(1_000L, 3_000L, 7_000L), retryTimes, "waits 1 s, 2 s, 4 s (jitter at cap)");
        assertEquals(4, attempts.get(), "4 attempts in total");
        assertEquals(ScheduledTask.State.DEAD, t.state(), "gave up");
        assertEquals(1, s.deadLetters().size(), "one dead letter");
        assertEquals("webhook", s.deadLetters().get(0).taskName(), "dead letter names the task");
        // full jitter: a random draw of 0.25 gives a quarter of the cap
        assertEquals(1_000L, new RetryPolicy(5, 1_000, 60_000).backoffMillis(3, () -> 0.25), "0.25 * 4 s");
        assertEquals(60_000L, new RetryPolicy(50, 1_000, 60_000).backoffMillis(40, () -> 1.0), "capped, no overflow");
        pass("retryWithBackoffThenDeadLetter");
    }

    static void recurringTaskSurvivesDeadLetter() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        var opts = TaskOptions.DEFAULT.withRetry(new RetryPolicy(2, 500, 500));
        ScheduledTask t = s.schedule("nightly", () -> { throw new RuntimeException("db down"); },
                new Schedule.FixedRate(10_000, 10_000), opts);
        clock.set(10_000); s.runDue();                              // fails, retry at 10.5 s
        clock.set(10_500); s.runDue();                              // fails again -> dead letter
        assertEquals(1, s.deadLetters().size(), "dead-lettered");
        assertEquals(20_000L, t.runAt(), "tomorrow's run is still planned, on the original grid");
        pass("recurringTaskSurvivesDeadLetter");
    }

    static long ms(String instant) { return Instant.parse(instant).toEpochMilli(); }

    static void cronNextFireTimes() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        var nightly = new CronExpression("0 2 * * *");
        assertEquals(ms("2026-10-08T20:30:00Z"), nightly.nextAfter(ms("2026-10-08T12:00:00Z"), ist),
                "02:00 IST = 20:30 UTC the day before");
        var workHours = new CronExpression("*/15 9-17 * * 1-5");
        // Friday 2026-10-09 17:50 IST -> next is Monday 09:00 IST
        assertEquals(ms("2026-10-12T03:30:00Z"), workHours.nextAfter(ms("2026-10-09T12:20:00Z"), ist), "skips weekend");
        var listsAndSteps = new CronExpression("0,30 8-12/2 * * *");
        // hours 8,10,12 x minutes 0,30; from 08:45 IST the next is 10:00 IST
        assertEquals(ms("2026-10-08T04:30:00Z"), listsAndSteps.nextAfter(ms("2026-10-08T03:15:00Z"), ist), "10:00 IST");
        // day-of-month AND day-of-week both restricted -> EITHER matches (classic cron rule)
        var firstOrFriday = new CronExpression("0 0 1 * 5");
        assertEquals(ms("2026-10-08T18:30:00Z"), firstOrFriday.nextAfter(ms("2026-10-08T00:00:00Z"), ist),
                "Friday 2026-10-09 00:00 IST, before the 1st of November");
        assertThrows(() -> new CronExpression("61 * * * *"), "minute 61");
        assertThrows(() -> new CronExpression("* * *"), "3 fields");
        pass("cronNextFireTimes");
    }

    static void cronDaylightSavingGapAndOverlap() {
        ZoneId ny = ZoneId.of("America/New_York");
        var at0230 = new CronExpression("30 2 * * *");
        // 2025-03-09: clocks jump 02:00 EST -> 03:00 EDT, so 02:30 does not exist that day
        long gapDay = at0230.nextAfter(ms("2025-03-08T17:00:00Z"), ny);
        assertEquals(ms("2025-03-09T07:00:00Z"), gapDay, "fires once at 03:00 EDT, when the gap ends");
        assertEquals(ms("2025-03-10T06:30:00Z"), at0230.nextAfter(gapDay, ny), "next day back to 02:30 EDT");
        // 2025-11-02: 01:00-01:59 happens twice (EDT, then EST)
        var at0130 = new CronExpression("30 1 * * *");
        long first = at0130.nextAfter(ms("2025-11-01T16:00:00Z"), ny);
        assertEquals(ms("2025-11-02T05:30:00Z"), first, "first 01:30 (EDT, UTC-4)");
        assertEquals(ms("2025-11-03T06:30:00Z"), at0130.nextAfter(first, ny), "not again at 01:30 EST; next day");
        pass("cronDaylightSavingGapAndOverlap");
    }

    static void misfireFireOnceNow() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask t = s.scheduleAtFixedRate("sync", runs::incrementAndGet, 10_000, 10_000);
        clock.set(35_000);                       // "down" for 35 s: runs at 10, 20, 30 were missed
        s.runDue();
        assertEquals(1, runs.get(), "missed runs collapse into ONE run");
        assertEquals(45_000L, t.runAt(), "schedule continues from now");
        pass("misfireFireOnceNow");
    }

    static void misfireSkip() {
        var clock = new ManualTimeSource(0);
        var s = newScheduler(clock);
        AtomicInteger runs = new AtomicInteger();
        var skip = TaskOptions.DEFAULT.withMisfire(MisfirePolicy.SKIP);
        ScheduledTask rate = s.schedule("sync", runs::incrementAndGet, new Schedule.FixedRate(10_000, 10_000), skip);
        ScheduledTask once = s.schedule("reminder", runs::incrementAndGet, new Schedule.Once(5_000), skip);
        clock.set(35_000);
        s.runDue();
        assertEquals(0, runs.get(), "nothing ran");
        assertEquals(40_000L, rate.runAt(), "jumped to the next slot on the original grid");
        assertEquals(ScheduledTask.State.DONE, once.state(), "stale one-shot dropped");
        clock.set(35_500);                       // within the threshold is NOT a misfire
        s.scheduleAtFixedRate("late-but-ok", runs::incrementAndGet, 0, 1_000);
        clock.set(36_400);
        s.runDue();
        assertEquals(1, runs.get(), "0.9 s late is under the 1 s threshold: runs");
        pass("misfireSkip");
    }

    /** The classic bug: the dispatcher sleeps 10 s for task A and misses task B added for +50 ms. */
    static void earlierTaskWakesBackgroundDispatcher() throws Exception {
        var s = new Scheduler(TimeSource.system());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        s.start(pool);
        s.schedule("far", () -> {}, 10_000);
        Thread.sleep(50);                        // dispatcher is now asleep, waiting ~10 s
        CountDownLatch ran = new CountDownLatch(1);
        long added = System.nanoTime();
        s.schedule("soon", ran::countDown, 50);
        boolean ok = ran.await(1, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - added) / 1_000_000;
        s.shutdown(1_000);
        assertEquals(true, ok, "earlier task ran without waiting for the far one (took " + tookMs + " ms)");
        pass("earlierTaskWakesBackgroundDispatcher");
    }

    static void slowRecurringTaskNeverOverlaps() throws Exception {
        var s = new Scheduler(TimeSource.system());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger running = new AtomicInteger(), maxRunning = new AtomicInteger(), runs = new AtomicInteger();
        s.start(pool);
        s.scheduleAtFixedRate("slow", () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(60);                    // 3x longer than the period
            running.decrementAndGet();
            runs.incrementAndGet();
        }, 0, 20);
        Thread.sleep(400);
        s.shutdown(1_000);
        assertEquals(1, maxRunning.get(), "never two runs of the same task at once, despite 4 workers");
        if (runs.get() < 3) throw new AssertionError("expected several runs, got " + runs.get());
        pass("slowRecurringTaskNeverOverlaps");
    }

    static void gracefulShutdown() throws Exception {
        var s = new Scheduler(TimeSource.system());
        s.start(Executors.newFixedThreadPool(1));
        CountDownLatch started = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        s.schedule("export", () -> { started.countDown(); Thread.sleep(100); events.add("export finished"); }, 0);
        s.schedule("later", () -> events.add("later ran"), 60_000);
        started.await(1, TimeUnit.SECONDS);
        assertEquals(true, s.shutdown(1_000), "running task finished within the timeout");
        assertEquals(List.of("export finished"), events, "running work drained; future work not started");
        assertThrows(() -> s.schedule("new", () -> {}, 0), "rejects new tasks after shutdown");
        pass("gracefulShutdown");
    }

    // ---------------- tiny assert helpers ----------------

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertThrows(Runnable r, String what) {
        try { r.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(what + ": expected an exception");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
