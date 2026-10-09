import java.util.*;

/** Maglev lookup table vs modulo hashing vs a 100-vnode hash ring: balance and disruption. JDK only. */
public class MaglevDemo {
    static final int M = 65537; // prime table size

    static long mix(long x) { // splitmix64 finaliser: cheap, well-spread 64-bit hash
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
    static long h(String s, long seed) { long x = seed; for (char c : s.toCharArray()) x = mix(x * 31 + c); return x; }

    /** Each backend has its own permutation of 0..M-1: (offset + j*skip) % M. Backends take turns claiming their next free slot. */
    static int[] maglevTable(List<String> backends) {
        int n = backends.size();
        int[] offset = new int[n], skip = new int[n], next = new int[n];
        for (int i = 0; i < n; i++) {
            offset[i] = (int) Long.remainderUnsigned(h(backends.get(i), 1), M);
            skip[i] = (int) Long.remainderUnsigned(h(backends.get(i), 2), M - 1) + 1;
        }
        int[] table = new int[M];
        Arrays.fill(table, -1);
        int filled = 0;
        while (true) {
            for (int i = 0; i < n; i++) {
                int slot;
                do { slot = (int) (((long) offset[i] + (long) next[i]++ * skip[i]) % M); } while (table[slot] != -1);
                table[slot] = i;
                if (++filled == M) return table;
            }
        }
    }

    static TreeMap<Long, String> ring(List<String> backends, int vnodes) {
        TreeMap<Long, String> r = new TreeMap<>();
        for (String b : backends) for (int v = 0; v < vnodes; v++) r.put(h(b + "#" + v, 3), b);
        return r;
    }
    static String ringLookup(TreeMap<Long, String> r, long k) {
        Map.Entry<Long, String> e = r.ceilingEntry(k);
        return (e != null ? e : r.firstEntry()).getValue();
    }

    public static void main(String[] a) {
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 10; i++) before.add("backend-" + i);
        List<String> after = new ArrayList<>(before);
        after.remove("backend-3"); // one server dies

        int[] t1 = maglevTable(before), t2 = maglevTable(after);
        int[] count = new int[10];
        for (int o : t1) count[o]++;
        int min = Arrays.stream(count).min().getAsInt(), max = Arrays.stream(count).max().getAsInt();
        System.out.printf("Maglev table: M=%,d, 10 backends, ideal share %.1f entries each%n", M, M / 10.0);
        System.out.printf("  entries per backend: min=%,d max=%,d (spread %.2f%%)%n", min, max, 100.0 * (max - min) / (M / 10.0));

        int moved = 0, forced = 0;
        for (int i = 0; i < M; i++) {
            String was = before.get(t1[i]), now = after.get(t2[i]);
            if (!was.equals(now)) { moved++; if (!was.equals("backend-3")) forced++; }
        }
        System.out.printf("%nRemove backend-3 (should move ~10%% of traffic, the dead server's share):%n");
        System.out.printf("  Maglev:        %.1f%% of entries changed owner (%.1f%% moved although their owner was alive)%n",
                100.0 * moved / M, 100.0 * forced / M);

        int K = 100_000, modMoved = 0, ringMoved = 0;
        TreeMap<Long, String> r1 = ring(before, 100), r2 = ring(after, 100);
        int[] ringCount = new int[10];
        for (int k = 0; k < K; k++) {
            long key = mix(k + 1000L);
            if (!before.get((int) Long.remainderUnsigned(key, 10)).equals(after.get((int) Long.remainderUnsigned(key, 9)))) modMoved++;
            String rb = ringLookup(r1, key);
            ringCount[Integer.parseInt(rb.substring(8))]++;
            if (!rb.equals(ringLookup(r2, key))) ringMoved++;
        }
        System.out.printf("  Modulo (hash%%N): %.1f%% of connections changed backend%n", 100.0 * modMoved / K);
        System.out.printf("  Ring, 100 vnodes: %.1f%% of connections changed backend%n", 100.0 * ringMoved / K);
        int rmin = Arrays.stream(ringCount).min().getAsInt(), rmax = Arrays.stream(ringCount).max().getAsInt();
        System.out.printf("%nLoad spread over %,d connections (ideal %,d each):%n", K, K / 10);
        System.out.printf("  Ring, 100 vnodes: min=%,d max=%,d (spread %.1f%%)%n", rmin, rmax, 100.0 * (rmax - rmin) / (K / 10.0));
        System.out.printf("  Maglev:           min=%,d max=%,d of %,d entries (spread %.2f%%)%n", min, max, M, 100.0 * (max - min) / (M / 10.0));
    }
}
