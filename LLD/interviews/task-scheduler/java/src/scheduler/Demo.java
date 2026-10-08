package scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** A readable 30-second timeline on a fake clock, then cron next-fire times across a DST change. */
public final class Demo {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        long start = ZonedDateTime.of(2026, 10, 8, 9, 0, 0, 0, IST).toInstant().toEpochMilli();
        var clock = new ManualTimeSource(start);
        var scheduler = new Scheduler(clock, 1_000, new Random(7)::nextDouble);

        scheduler.schedule("reminder", () -> log(clock, "reminder: 'your cart is waiting' sent"), 5_000);
        scheduler.scheduleAtFixedRate("heartbeat", () -> log(clock, "heartbeat"), 0, 10_000);

        AtomicInteger webhookAttempts = new AtomicInteger();
        var retry = TaskOptions.DEFAULT.withRetry(new RetryPolicy(5, 1_000, 30_000));
        scheduler.schedule("webhook", () -> {
            int n = webhookAttempts.incrementAndGet();
            if (n < 3) { log(clock, "webhook attempt " + n + " -> HTTP 503, will retry"); throw new RuntimeException("503"); }
            log(clock, "webhook attempt " + n + " -> HTTP 200");
        }, new Schedule.Once(2_000), retry);

        var threeTries = TaskOptions.DEFAULT.withRetry(new RetryPolicy(3, 2_000, 30_000));
        scheduler.schedule("export", () -> { log(clock, "export failed: disk full"); throw new RuntimeException("disk full"); },
                new Schedule.Once(12_000), threeTries);

        ScheduledTask autoClose = scheduler.schedule("auto-close", () -> log(clock, "ticket auto-closed"), 20_000);

        for (int second = 0; second <= 30; second++) {
            if (second == 8) { autoClose.cancel(); log(clock, "user replied -> auto-close cancelled"); }
            scheduler.runDue();
            clock.advance(1_000);
        }
        System.out.println("\nDead letters:");
        scheduler.deadLetters().forEach(d -> System.out.println("  " + d.taskName() + " after " + d.attempts()
                + " attempts, last error: " + d.lastError()));

        System.out.println("\nCron '30 2 * * *' in America/New_York around the 2025-03-09 spring-forward:");
        printNext("30 2 * * *", ZoneId.of("America/New_York"), "2025-03-07T12:00:00Z", 4);
        System.out.println("\nCron '0 2 * * *' (nightly report) in Asia/Kolkata (no DST):");
        printNext("0 2 * * *", IST, "2026-10-08T00:00:00Z", 2);
    }

    static void log(TimeSource clock, String msg) {
        System.out.println("  " + HMS.format(Instant.ofEpochMilli(clock.nowMillis()).atZone(IST)) + " IST  " + msg);
    }

    static void printNext(String cron, ZoneId zone, String fromUtc, int n) {
        var expr = new CronExpression(cron);
        long t = Instant.parse(fromUtc).toEpochMilli();
        for (int i = 0; i < n; i++) {
            t = expr.nextAfter(t, zone);
            Instant at = Instant.ofEpochMilli(t);
            System.out.println("  " + at.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
                    + "   (" + at + ")");
        }
    }
}
