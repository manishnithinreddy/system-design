package logging;

import java.time.Instant;

/** Prints the same kind of events in pattern and JSON layout, then shows an async appender dropping under load. */
public final class Demo {
    public static void main(String[] args) {
        var clock = new ManualClock(Instant.parse("2026-10-08T03:30:00Z"));
        var ctx = new LoggerContext(clock);
        ctx.root().addAppender(new ConsoleAppender(System.out, new PatternLayout()));

        System.out.println("--- pattern layout (ROOT = INFO) ---");
        Logger orders = ctx.getLogger("com.shop.orders.OrderService");
        Logger db = ctx.getLogger("com.shop.db.Pool");
        MDC.put("requestId", "req-7f3a");
        orders.info("order {} placed with {} items", "A-1001", 3);
        db.debug("this DEBUG line is filtered out (level check, nothing formatted)");
        ctx.setLevel("com.shop.db", Level.DEBUG);                    // like POST /actuator/loggers/com.shop.db
        clock.advanceMillis(15);
        db.debug("borrowed connection {} after {} ms", "conn-4", 12);
        clock.advanceMillis(30);
        orders.error("payment call failed for order {}", "A-1001", new IllegalStateException("connection refused"));

        System.out.println();
        System.out.println("--- JSON layout + redaction on com.shop.payment (additivity off) ---");
        Logger pay = ctx.getLogger("com.shop.payment.Gateway");
        Logger payParent = ctx.getLogger("com.shop.payment");
        payParent.addAppender(new ConsoleAppender(System.out, new RedactingLayout(new JsonLayout())));
        payParent.setAdditive(false);
        MDC.put("userId", "u-42");
        clock.advanceMillis(5);
        pay.warn("retrying charge, provider said \"{}\" token={}", "rate limited\nretry later", "sk_live_abc123");
        MDC.clear();

        System.out.println();
        System.out.println("--- async appender, DROP policy, slow sink (1 ms per event), queue of 8 ---");
        Appender slowSink = e -> { try { Thread.sleep(1); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } };
        var async = new AsyncAppender(slowSink, 8, AsyncAppender.OverflowPolicy.DROP);
        Logger noisy = ctx.getLogger("com.shop.noisy");
        noisy.setAdditive(false);
        noisy.addAppender(async);
        for (int i = 0; i < 1_000; i++) noisy.info("event {}", i);
        async.close();
        System.out.println("logged 1000, dropped " + async.dropped() + " (exact number varies run to run)");
        ctx.close();
    }
}
