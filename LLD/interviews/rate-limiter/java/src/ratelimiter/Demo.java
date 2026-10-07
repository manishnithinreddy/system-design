package ratelimiter;

import java.time.Duration;

/** Runs with the real clock: 5 requests/second per user, 12 requests fired 100ms apart. */
public final class Demo {
    public static void main(String[] args) throws InterruptedException {
        RateLimiterFactory factory = new RateLimiterFactory(TimeSource.SYSTEM);
        try (KeyedRateLimiter limiter = new KeyedRateLimiter(
                key -> RateLimitConfig.of(Algorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(1)),
                factory, TimeSource.SYSTEM, Duration.ofMinutes(10), true)) {

            for (int i = 1; i <= 12; i++) {
                boolean ok = limiter.tryAcquire("user-42");
                System.out.printf("request %2d -> %s%n", i, ok ? "200 OK" : "429 Too Many Requests");
                if (i == 7) {
                    System.out.println("   ...waiting 1s (bucket refills)...");
                    Thread.sleep(1000);
                }
            }
        }
    }
}
