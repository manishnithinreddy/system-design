import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Allocates lots of short-lived garbage while holding a ~200 MB "live set"
 * (like a cache). Measures how long each loop iteration takes; the slowest
 * iterations reveal GC pauses that stopped the thread.
 * Run: java -Xmx512m -XX:+UseG1GC -Xlog:gc GcDemo   (or -XX:+UseZGC / -XX:+UseSerialGC)
 */
public class GcDemo {
    static final int LIVE_ENTRIES = 200_000;      // 200k * ~1 KB = ~200 MB live
    static final int ITERATIONS = 3_000_000;
    static byte[][] cache = new byte[LIVE_ENTRIES][];

    public static void main(String[] args) {
        Random rnd = new Random(42);
        for (int i = 0; i < LIVE_ENTRIES; i++) cache[i] = new byte[1024];

        long[] worst = new long[5];          // five slowest iterations in ns
        long total = 0, start = System.nanoTime();
        List<Object> sink = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            byte[] garbage = new byte[2048];                 // short-lived
            garbage[0] = (byte) i;
            if (i % 50 == 0) cache[rnd.nextInt(LIVE_ENTRIES)] = new byte[1024]; // churn the live set
            if (i % 100_000 == 0) { sink.clear(); }
            sink.add(garbage.length);
            long dt = System.nanoTime() - t0;
            total += dt;
            for (int k = 0; k < worst.length; k++) {
                if (dt > worst[k]) {
                    long tmp = worst[k]; worst[k] = dt; dt = tmp;   // insert, shift the rest down
                }
            }
        }
        long wall = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("gc=%s wall=%d ms avg=%.0f ns/iter%n", gcNames(), wall, total / (double) ITERATIONS);
        System.out.print("slowest iterations (ms): ");
        for (long w : worst) System.out.printf("%.2f ", w / 1e6);
        System.out.println();
    }

    static String gcNames() {
        StringBuilder sb = new StringBuilder();
        for (var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans())
            sb.append(b.getName()).append('/');
        return sb.toString();
    }
}
