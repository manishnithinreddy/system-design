import java.security.MessageDigest;
import java.util.*;

/**
 * Fixed-size chunking vs content-defined chunking (CDC) with a Gear rolling hash.
 * Chunks 4 MB of random data, makes a small edit, and counts how many chunks
 * a dedup-ing backup tool would have to upload again.
 * Run: java ChunkingDemo.java
 */
public class ChunkingDemo {
    static final int FIXED = 8 * 1024;
    static final int MIN = 2 * 1024, MAX = 64 * 1024;
    static final long MASK = 0x1FFFL << 51;   // top 13 bits must be zero: 1 in 2^13 = 8,192 positions cuts
    static final long[] GEAR = new long[256];  // one random 64-bit number per byte value
    static { Random r = new Random(1); for (int i = 0; i < 256; i++) GEAR[i] = r.nextLong(); }

    /** Fixed-size: cut every 8 KB, whatever the content. */
    static List<int[]> fixed(byte[] d) {
        List<int[]> out = new ArrayList<>();
        for (int s = 0; s < d.length; s += FIXED) out.add(new int[]{s, Math.min(FIXED, d.length - s)});
        return out;
    }

    /** CDC: roll a Gear hash, cut where its top 13 bits are zero (respecting MIN and MAX). */
    static List<int[]> cdc(byte[] d) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        while (start < d.length) {
            int end = Math.min(start + MAX, d.length), cut = end;
            long h = 0;
            for (int i = start; i < end; i++) {
                h = (h << 1) + GEAR[d[i] & 0xFF];   // O(1) per byte; old bytes fall off the top after 64 shifts
                if (i - start + 1 >= MIN && (h & MASK) == 0) { cut = i + 1; break; }
            }
            out.add(new int[]{start, cut - start});
            start = cut;
        }
        return out;
    }

    static String sha256(byte[] d, int off, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(d, off, len);
        return HexFormat.of().formatHex(md.digest());
    }

    static void compare(String method, List<int[]> a, byte[] oldD, List<int[]> b, byte[] newD) throws Exception {
        Set<String> stored = new HashSet<>();
        for (int[] c : a) stored.add(sha256(oldD, c[0], c[1]));
        int shared = 0; long upload = 0;
        for (int[] c : b) {
            if (stored.contains(sha256(newD, c[0], c[1]))) shared++;
            else upload += c[1];
        }
        System.out.printf("  %-6s chunks old %4d, new %4d | shared %4d (%5.1f%%) | re-upload %,10d bytes (%5.1f%% of file)%n",
                method, a.size(), b.size(), shared, 100.0 * shared / b.size(), upload, 100.0 * upload / newD.length);
    }

    public static void main(String[] args) throws Exception {
        byte[] old = new byte[4 << 20];                      // 4 MB
        new Random(42).nextBytes(old);

        List<int[]> cOld = cdc(old);
        int minSeen = Integer.MAX_VALUE, maxSeen = 0;
        for (int[] c : cOld) { minSeen = Math.min(minSeen, c[1]); maxSeen = Math.max(maxSeen, c[1]); }
        System.out.printf("file %,d bytes. CDC: %d chunks, average %,d bytes, smallest %,d, largest %,d%n%n",
                old.length, cOld.size(), old.length / cOld.size(), minSeen, maxSeen);

        // Edit 1: overwrite 1 byte in the middle (nothing shifts)
        byte[] e1 = old.clone(); e1[2_000_000] ^= 1;
        System.out.println("edit 1: change 1 byte at offset 2,000,000 (no shift)");
        compare("fixed", fixed(old), old, fixed(e1), e1);
        compare("CDC", cOld, old, cdc(e1), e1);

        // Edit 2: insert 1 byte at offset 100 (everything after it shifts by one)
        byte[] e2 = new byte[old.length + 1];
        System.arraycopy(old, 0, e2, 0, 100); e2[100] = 7; System.arraycopy(old, 100, e2, 101, old.length - 100);
        System.out.println("\nedit 2: insert 1 byte at offset 100 (everything after it shifts)");
        compare("fixed", fixed(old), old, fixed(e2), e2);
        compare("CDC", cOld, old, cdc(e2), e2);
    }
}
