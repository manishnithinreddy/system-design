import java.util.*;

/**
 * HyperLogLog with m = 2^14 = 16,384 registers (the same size Redis uses).
 * Counts millions of distinct items in ~12 KB, then merges two sketches.
 * Run: java -Xmx2g HyperLogLogDemo.java
 */
public class HyperLogLogDemo {
    static final int P = 14;                 // first 14 bits of the hash pick the register
    static final int M = 1 << P;             // 16,384 registers

    static final class HLL {
        final byte[] reg = new byte[M];      // each register needs only 6 bits (values 0..51); a byte is simpler here

        void add(long item) {
            long h = hash(item);
            int idx = (int) (h >>> (64 - P));                    // top 14 bits -> which register
            long rest = h << P;                                  // remaining 50 bits
            int rank = Math.min(Long.numberOfLeadingZeros(rest), 64 - P) + 1;   // position of the first 1-bit
            if (rank > reg[idx]) reg[idx] = (byte) rank;         // keep the MAX seen in this register
        }

        double estimate() {
            double sum = 0; int zeros = 0;
            for (byte r : reg) { sum += 1.0 / (1L << r); if (r == 0) zeros++; }
            double alpha = 0.7213 / (1 + 1.079 / M);
            double raw = alpha * M * M / sum;                    // harmonic mean of 2^register, scaled
            if (raw <= 2.5 * M && zeros > 0) return M * Math.log((double) M / zeros);   // small range: linear counting
            return raw;
        }

        HLL merge(HLL other) {                                    // union = register-wise max
            HLL out = new HLL();
            for (int i = 0; i < M; i++) out.reg[i] = (byte) Math.max(reg[i], other.reg[i]);
            return out;
        }
    }

    /** SplitMix64 finalizer: turns 1, 2, 3... into well-scrambled 64-bit values. */
    static long hash(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    static void report(String label, double est, long truth) {
        System.out.printf("%-32s true %,12d   estimate %,12.0f   error %+6.2f%%%n", label, truth, est, 100.0 * (est - truth) / truth);
    }

    public static void main(String[] args) {
        System.out.printf("registers m = %,d, standard error 1.04/sqrt(m) = %.2f%%%n", M, 104.0 / Math.sqrt(M));
        System.out.printf("sketch memory: %,d registers x 6 bits = %,d bytes (always, for any count)%n%n", M, M * 6 / 8);

        for (long n : new long[]{1_000, 100_000, 1_000_000, 10_000_000}) {
            HLL h = new HLL();
            for (long i = 0; i < n; i++) h.add(i);
            report("distinct user ids", h.estimate(), n);
        }

        // One run can be unlucky. Repeat 1M with 20 different id ranges and look at the spread.
        Random rnd = new Random(7); double sumSq = 0, worst = 0;
        for (int run = 0; run < 20; run++) {
            HLL h = new HLL(); long offset = rnd.nextLong();
            for (long i = 0; i < 1_000_000; i++) h.add(offset + i);
            double err = 100.0 * (h.estimate() - 1_000_000) / 1_000_000;
            sumSq += err * err; worst = Math.max(worst, Math.abs(err));
        }
        System.out.printf("20 runs of 1M: typical (rms) error %.2f%%, worst %.2f%%%n", Math.sqrt(sumSq / 20), worst);

        // Duplicates do not change the sketch: add the same 1M users 5 times.
        HLL dup = new HLL();
        for (int round = 0; round < 5; round++) for (long i = 0; i < 1_000_000; i++) dup.add(i);
        report("1M users, each seen 5 times", dup.estimate(), 1_000_000);

        // Merging: server A saw users 0..6M, server B saw users 4M..10M (2M overlap). Union = 10M.
        HLL a = new HLL(), b = new HLL();
        for (long i = 0; i < 6_000_000; i++) a.add(i);
        for (long i = 4_000_000; i < 10_000_000; i++) b.add(i);
        System.out.println();
        report("server A alone", a.estimate(), 6_000_000);
        report("server B alone", b.estimate(), 6_000_000);
        report("A + B summed (double counts)", a.estimate() + b.estimate(), 10_000_000);
        report("merge(A, B) = register max", a.merge(b).estimate(), 10_000_000);

        // The exact alternative: a HashSet<Long> of 1M ids. Measure the heap it takes.
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        Set<Long> exact = new HashSet<>();
        for (long i = 0; i < 1_000_000; i++) exact.add(hash(i));
        System.gc();
        long after = rt.totalMemory() - rt.freeMemory();
        System.out.printf("%nHashSet<Long> with %,d ids: ~%,d MB of heap (~%d bytes per id)%n",
                exact.size(), (after - before) >> 20, (after - before) / exact.size());
        System.out.printf("HyperLogLog for the same ids:  %,d bytes%n", M * 6 / 8);
    }
}
