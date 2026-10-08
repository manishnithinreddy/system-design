import java.security.MessageDigest;
import java.util.*;

/**
 * The rsync algorithm (Tridgell & Mackerras, 1996) on an in-memory 1 MB "file".
 * Receiver has OLD, sender has NEW. Receiver sends per-block checksums; sender
 * slides a window over NEW one byte at a time and answers "copy block k" or literal bytes.
 * Run: java RollingHashDemo.java
 */
public class RollingHashDemo {
    static final int B = 2048;            // block size
    static final int MOD = 1 << 16;       // weak checksum halves are kept mod 2^16

    /** Weak checksum computed from scratch: a = sum of bytes, b = sum of running a's. */
    static int[] weakFull(byte[] d, int off, int len) {
        int a = 0, b = 0;
        for (int i = 0; i < len; i++) { a += d[off + i] & 0xFF; b += (len - i) * (d[off + i] & 0xFF); }
        return new int[]{a % MOD, b % MOD};
    }

    static String strong(byte[] d, int off, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(d, off, len);
        return HexFormat.of().formatHex(md.digest());
    }

    public static void main(String[] args) throws Exception {
        Random rnd = new Random(42);
        byte[] oldF = new byte[1 << 20];                      // 1 MB, 512 blocks of 2 KB
        rnd.nextBytes(oldF);

        // NEW = 3 bytes inserted at the start, 100 bytes overwritten mid-file, 50 bytes inserted at ~700 KB
        byte[] ins1 = "HI!".getBytes(), ins2 = new byte[50]; rnd.nextBytes(ins2);
        byte[] mid = oldF.clone();
        for (int i = 0; i < 100; i++) mid[500_000 + i] ^= 0x5A;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(ins1); out.write(mid, 0, 700_000); out.write(ins2); out.write(mid, 700_000, mid.length - 700_000);
        byte[] newF = out.toByteArray();

        // ---- Receiver: signature of OLD (weak + strong per block) ----
        int blocks = oldF.length / B;
        Map<Integer, List<Integer>> table = new HashMap<>();
        String[] strongs = new String[blocks];
        for (int k = 0; k < blocks; k++) {
            int[] w = weakFull(oldF, k * B, B);
            table.computeIfAbsent(w[0] + (w[1] << 16), x -> new ArrayList<>()).add(k);
            strongs[k] = strong(oldF, k * B, B);
        }
        long sigBytes = (long) blocks * (4 + 16);           // 4-byte weak + 16-byte MD5 per block

        // ---- Sender: slide over NEW ----
        List<Object> instr = new ArrayList<>();              // Integer = copy block k, byte[] = literal run
        java.io.ByteArrayOutputStream lit = new java.io.ByteArrayOutputStream();
        int i = 0, weakHits = 0, falseWeak = 0;
        int[] w = newF.length >= B ? weakFull(newF, 0, B) : null;
        while (i < newF.length) {
            Integer match = null;
            if (i + B <= newF.length) {
                List<Integer> cands = table.get(w[0] + (w[1] << 16));
                if (cands != null) {
                    weakHits++;
                    String s = strong(newF, i, B);            // strong hash only when the cheap one matches
                    for (int k : cands) if (strongs[k].equals(s)) { match = k; break; }
                    if (match == null) falseWeak++;
                }
            }
            if (match != null) {
                if (lit.size() > 0) { instr.add(lit.toByteArray()); lit.reset(); }
                instr.add(match);
                i += B;                                       // jump a whole block, restart the checksum
                if (i + B <= newF.length) w = weakFull(newF, i, B);
            } else {
                lit.write(newF[i]);
                if (i + B < newF.length) {                    // roll the window one byte: O(1)
                    int out1 = newF[i] & 0xFF, in1 = newF[i + B] & 0xFF;
                    w[0] = Math.floorMod(w[0] - out1 + in1, MOD);
                    w[1] = Math.floorMod(w[1] - B * out1 + w[0], MOD);
                }
                i++;
            }
        }
        if (lit.size() > 0) instr.add(lit.toByteArray());

        // ---- Receiver: rebuild NEW from OLD + instructions ----
        java.io.ByteArrayOutputStream rebuilt = new java.io.ByteArrayOutputStream();
        int copies = 0; long literalBytes = 0, runs = 0;
        for (Object o : instr) {
            if (o instanceof Integer k) { rebuilt.write(oldF, k * B, B); copies++; }
            else { byte[] r = (byte[]) o; rebuilt.write(r); literalBytes += r.length; runs++; }
        }
        long deltaBytes = copies * 4L + runs * 4L + literalBytes;   // 4-byte block index or 4-byte length header

        // ---- For comparison: no rolling, only compare blocks at the same offsets ----
        int sameOffset = 0;
        for (int k = 0; k < blocks && (k + 1) * B <= newF.length; k++)
            if (strongs[k].equals(strong(newF, k * B, B))) sameOffset++;

        System.out.printf("old file %,d bytes, new file %,d bytes, block size %,d -> %d blocks%n", oldF.length, newF.length, B, blocks);
        System.out.printf("edits: +3 bytes at offset 0, 100 bytes changed at ~500 KB, +50 bytes at ~700 KB%n%n");
        System.out.printf("blocks matched at the SAME offset (no rolling): %d of %d%n%n", sameOffset, blocks);
        System.out.printf("rolling search: weak-checksum hits %,d, of which false alarms %d%n", weakHits, falseWeak);
        System.out.printf("matched blocks (copy instructions): %d of %d%n", copies, blocks);
        System.out.printf("literal bytes sent: %,d in %d runs%n", literalBytes, runs);
        System.out.printf("receiver -> sender (signatures): %,d bytes%n", sigBytes);
        System.out.printf("sender -> receiver (delta):      %,d bytes%n", deltaBytes);
        System.out.printf("total on the wire: %,d bytes = %.1f%% of the %,d-byte file%n",
                sigBytes + deltaBytes, 100.0 * (sigBytes + deltaBytes) / newF.length, newF.length);
        System.out.println("rebuilt == new file: " + Arrays.equals(rebuilt.toByteArray(), newF));
    }
}
