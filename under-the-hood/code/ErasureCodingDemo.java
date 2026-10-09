import java.util.*;

/**
 * Erasure coding from scratch:
 *  part 1: XOR parity, 4 data shards + 1 parity (RAID 5 style), survives any 1 lost shard.
 *  part 2: Reed-Solomon over GF(256), k=4 data + m=2 parity, survives ANY 2 lost shards.
 * Run: java ErasureCodingDemo.java
 */
public class ErasureCodingDemo {
    static final int K = 4, M = 2, N = K + M;

    // ---------- GF(256): arithmetic on bytes where + is XOR and * uses log tables ----------
    static final int[] EXP = new int[512], LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {          // powers of the generator 2, modulo x^8+x^4+x^3+x^2+1 (0x11D)
            EXP[i] = x; LOG[x] = i;
            x <<= 1; if (x >= 256) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];   // so mul never needs a "% 255"
    }
    static int mul(int a, int b) { return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]]; }
    static int inv(int a) { return EXP[255 - LOG[a]]; }          // a * inv(a) = 1

    /** Encoding matrix: N rows x K cols. Top K rows = identity (data stored as-is),
     *  bottom M rows = Cauchy matrix 1/(x_i + y_j). Any K rows of it form an invertible matrix. */
    static int[][] encodingMatrix() {
        int[][] g = new int[N][K];
        for (int i = 0; i < K; i++) g[i][i] = 1;
        for (int i = 0; i < M; i++)
            for (int j = 0; j < K; j++) g[K + i][j] = inv((K + i) ^ j);   // x_i = K+i, y_j = j, never equal
        return g;
    }

    /** shard[row] = sum over j of g[row][j] * data[j], byte by byte ("sum" is XOR). */
    static byte[] combine(int[] coeffs, byte[][] inputs) {
        byte[] out = new byte[inputs[0].length];
        for (int j = 0; j < coeffs.length; j++) {
            if (coeffs[j] == 0) continue;
            for (int b = 0; b < out.length; b++) out[b] ^= (byte) mul(coeffs[j], inputs[j][b] & 0xFF);
        }
        return out;
    }

    /** Invert a K x K matrix over GF(256) with Gauss-Jordan elimination. */
    static int[][] invert(int[][] a) {
        int n = a.length; int[][] m = new int[n][2 * n];
        for (int i = 0; i < n; i++) { System.arraycopy(a[i], 0, m[i], 0, n); m[i][n + i] = 1; }
        for (int c = 0; c < n; c++) {
            int p = c; while (m[p][c] == 0) p++;                 // Cauchy guarantees a pivot exists
            int[] t = m[c]; m[c] = m[p]; m[p] = t;
            int f = inv(m[c][c]); for (int j = 0; j < 2 * n; j++) m[c][j] = mul(m[c][j], f);
            for (int r = 0; r < n; r++) if (r != c && m[r][c] != 0) {
                int g = m[r][c]; for (int j = 0; j < 2 * n; j++) m[r][j] ^= mul(g, m[c][j]);
            }
        }
        int[][] out = new int[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, out[i], 0, n);
        return out;
    }

    /** Rebuild all N shards from any K survivors (lost shards are null). */
    static byte[][] reconstruct(int[][] g, byte[][] shards) {
        int[] rows = new int[K]; byte[][] have = new byte[K][];
        for (int i = 0, c = 0; i < N && c < K; i++) if (shards[i] != null) { rows[c] = i; have[c++] = shards[i]; }
        int[][] sub = new int[K][]; for (int i = 0; i < K; i++) sub[i] = g[rows[i]];
        int[][] decode = invert(sub);                            // survivors = sub * data  =>  data = sub^-1 * survivors
        byte[][] data = new byte[K][]; for (int i = 0; i < K; i++) data[i] = combine(decode[i], have);
        byte[][] all = new byte[N][]; for (int i = 0; i < N; i++) all[i] = i < K ? data[i] : combine(g[i], data);
        return all;
    }

    public static void main(String[] args) {
        // ---------------- Part 1: XOR parity ----------------
        System.out.println("== Part 1: XOR parity, 4 data shards + 1 parity ==");
        int[] d = {0x5A, 0x3C, 0xF0, 0x0F};
        int p = d[0] ^ d[1] ^ d[2] ^ d[3];
        System.out.printf("one byte from each shard: D0=%02X D1=%02X D2=%02X D3=%02X -> P = D0^D1^D2^D3 = %02X%n", d[0], d[1], d[2], d[3], p);
        System.out.printf("lose D2: D0^D1^D3^P = %02X (was %02X)%n", d[0] ^ d[1] ^ d[3] ^ p, d[2]);

        Random rnd = new Random(42);
        int size = 1 << 20, shardSize = size / K;
        byte[] file = new byte[size]; rnd.nextBytes(file);
        byte[][] xs = new byte[K + 1][shardSize];
        for (int i = 0; i < K; i++) System.arraycopy(file, i * shardSize, xs[i], 0, shardSize);
        for (int i = 0; i < K; i++) for (int b = 0; b < shardSize; b++) xs[K][b] ^= xs[i][b];
        for (int lost = 0; lost <= K; lost++) {
            byte[] rebuilt = new byte[shardSize];
            for (int i = 0; i <= K; i++) if (i != lost) for (int b = 0; b < shardSize; b++) rebuilt[b] ^= xs[i][b];
            System.out.printf("1 MB file: lose shard %d, XOR the other 4 -> identical: %b%n", lost, Arrays.equals(rebuilt, xs[lost]));
        }

        // ---------------- Part 2: Reed-Solomon RS(4,2) ----------------
        System.out.println("\n== Part 2: Reed-Solomon over GF(256), k=4 data + m=2 parity ==");
        int[][] g = encodingMatrix();
        System.out.println("parity rows of the encoding matrix (Cauchy):");
        for (int i = K; i < N; i++) System.out.println("  P" + (i - K) + " = " + Arrays.toString(g[i]) + " . [D0 D1 D2 D3]");
        byte[][] data = new byte[K][shardSize];
        for (int i = 0; i < K; i++) System.arraycopy(file, i * shardSize, data[i], 0, shardSize);
        byte[][] shards = new byte[N][];
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) shards[i] = combine(g[i], data);
        System.out.printf("encoded 1 MB into 6 shards of %,d bytes in %d ms%n", shardSize, (System.nanoTime() - t0) / 1_000_000);

        int ok = 0, tried = 0;
        for (int a = 0; a < N; a++) for (int b = a + 1; b < N; b++) {
            byte[][] damaged = shards.clone(); damaged[a] = null; damaged[b] = null;
            byte[][] fixed = reconstruct(g, damaged);
            boolean same = true; for (int i = 0; i < N; i++) same &= Arrays.equals(fixed[i], shards[i]);
            tried++; if (same) ok++;
            System.out.printf("  lose %s + %s -> rebuilt, bytes identical: %b%n", name(a), name(b), same);
        }
        System.out.printf("%d of %d two-shard losses recovered%n", ok, tried);
        byte[][] three = shards.clone(); three[0] = three[1] = three[2] = null;
        long left = Arrays.stream(three).filter(Objects::nonNull).count();
        System.out.println("lose 3 shards -> only " + left + " left, fewer than k=4: data is gone (RS(4,2) tolerates 2)");

        // ---------------- Cost summary ----------------
        System.out.println("\n== Cost for this 1 MB file ==");
        System.out.printf("3x replication : stores %,9d bytes (3.00x), survives 2 lost copies, repair 1 copy reads %,9d bytes%n", 3 * size, size);
        System.out.printf("XOR 4+1        : stores %,9d bytes (%.2fx), survives 1 lost shard,  repair 1 shard reads %,9d bytes%n", 5 * shardSize, 5.0 / 4, 4 * shardSize);
        System.out.printf("RS(4,2)        : stores %,9d bytes (%.2fx), survives 2 lost shards, repair 1 shard reads %,9d bytes%n", N * shardSize, (double) N / K, K * shardSize);
        System.out.printf("RS repair reads %d shards to rebuild 1: %,d bytes over the network to recreate %,d bytes%n", K, K * shardSize, shardSize);
    }

    static String name(int i) { return i < K ? "D" + i : "P" + (i - K); }
}
