import java.util.*;

/**
 * Video compression in miniature: a 128x72 grayscale "video" (30 frames, a gradient
 * background and a textured square moving 3 px right and 1 px down per frame), coded
 *  (1) raw, (2) as I-frames only (8x8 DCT + quantisation at three quality levels),
 *  (3) as one I-frame + P-frames (motion search + quantised residual), and
 *  (4) drawn as ASCII art at high vs low quality so the blocks are visible.
 * "Non-zero coefficients" stands in for bytes: entropy coding makes zeros nearly free.
 * Run: java VideoCompressionDemo.java
 */
public class VideoCompressionDemo {
    static final int W = 128, H = 72, FRAMES = 30, B = 8, SEARCH = 7, MV_COST = 4;
    static final double NOISE = 0.0;                     // try 2.0: camera noise / film grain
    static final int[] JPEG_LUMA = {                     // JPEG standard table (Annex K): big steps for fine detail
        16, 11, 10, 16, 24, 40, 51, 61,  12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,  14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68,109,103, 77,  24, 35, 55, 64, 81,104,113, 92,
        49, 64, 78, 87,103,121,120,101,  72, 92, 95, 98,112,100,103, 99};
    static final double[][] C = new double[B][B];        // DCT basis: C[u][x] = "cosine wave u sampled at pixel x"
    static {
        for (int u = 0; u < B; u++) for (int x = 0; x < B; x++)
            C[u][x] = (u == 0 ? Math.sqrt(1.0 / B) : Math.sqrt(2.0 / B)) * Math.cos((2 * x + 1) * u * Math.PI / (2 * B));
    }

    public static void main(String[] args) {
        double[][][] video = makeVideo();
        int raw = W * H * FRAMES;
        System.out.printf("video: %dx%d grayscale, %d frames, 1 byte per pixel%n", W, H, FRAMES);
        System.out.printf("raw size: %d x %d x %d = %,d bytes (%,d values per frame)%n%n", W, H, FRAMES, raw, W * H);

        System.out.println("(2) I-frames only: every frame coded on its own (8x8 DCT + quantise)");
        double[] scales = {0.25, 1, 8};
        String[] names = {"high", "medium", "low"};
        double[][] hi = null, lo = null;
        for (int q = 0; q < 3; q++) {
            long nz = 0; double mse = 0;
            for (int f = 0; f < FRAMES; f++) {
                double[][] rec = new double[H][W];
                nz += codeFrame(video[f], null, null, rec, scales[q]);
                mse += mse(video[f], rec) / FRAMES;
                if (f == 15 && q == 0) hi = rec;
                if (f == 15 && q == 2) lo = rec;
            }
            System.out.printf("  %-6s (table x %.2f): %,7d non-zero coefficients = %5.1f%% of raw values | PSNR %.1f dB%n",
                names[q], scales[q], nz, 100.0 * nz / raw, psnr(mse));
        }

        System.out.println("\n(3) I + P frames, medium quality: frame 0 is an I-frame, frames 1-29 predict from the previous one");
        double[][] prev = new double[H][W];
        long iNz = codeFrame(video[0], null, null, prev, 1), pNz = 0, mvNonZero = 0; double mse = mse(video[0], prev) / FRAMES;
        Map<String, Integer> mvCount = new TreeMap<>();
        for (int f = 1; f < FRAMES; f++) {
            int[][] mv = new int[(H / B) * (W / B)][];
            double[][] rec = new double[H][W];
            pNz += codeFrame(video[f], prev, mv, rec, 1);
            mse += mse(video[f], rec) / FRAMES;
            for (int[] v : mv) if (v[0] != 0 || v[1] != 0) { mvNonZero++; mvCount.merge("(" + v[0] + "," + v[1] + ")", 1, Integer::sum); }
            if (f == 15) printSomeVectors(mv);
            prev = rec;
        }
        System.out.printf("  I-frame: %,d non-zero coefficients%n", iNz);
        System.out.printf("  P-frame: %,.0f non-zero coefficients on average + %d blocks with a motion vector (%,d blocks with a zero vector)%n",
            pNz / (FRAMES - 1.0), mvNonZero / (FRAMES - 1), (H / B) * (W / B) - mvNonZero / (FRAMES - 1));
        System.out.printf("  => a P-frame needs %.1f%% of the coefficients of an I-frame | whole clip PSNR %.1f dB%n",
            100.0 * pNz / (FRAMES - 1) / iNz, psnr(mse));
        System.out.printf("  whole clip: %,d coefficients (I+P) vs %,d (all I-frames) for the same quality setting%n", iNz + pNz, iNz * FRAMES);
        System.out.print("  most common non-zero vectors over all P-frames (dx,dy) x count:");
        mvCount.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
            .forEach(e -> System.out.print(" " + e.getKey() + " x" + e.getValue()));
        System.out.println();

        System.out.println("\n(4) frame 15, a 48x24 crop around the square: high quality (left) vs low quality (right)");
        String ramp = " .:-=+*#%@";
        for (int y = 24; y < 48; y++) {
            StringBuilder s = new StringBuilder("  ");
            for (double[][] img : new double[][][]{hi, lo}) {
                for (int x = 44; x < 92; x++) s.append(ramp.charAt((int) Math.max(0, Math.min(9, img[y][x] / 25.6))));
                s.append("   ");
            }
            System.out.println(s);
        }
    }

    /** Background: smooth diagonal gradient. Foreground: 24x24 textured square moving (+3, +1) px per frame. */
    static double[][][] makeVideo() {
        Random r = new Random(7);
        double[][] tex = new double[12][12];                 // the square's own texture: random 2x2-pixel patches
        for (double[] row : tex) for (int i = 0; i < 12; i++) row[i] = 140 + r.nextInt(100);
        double[][][] v = new double[FRAMES][H][W];
        for (int f = 0; f < FRAMES; f++) {
            int sx = 8 + 3 * f, sy = 12 + f;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                double p = 30 + 90.0 * x / W + 40.0 * y / H;
                if (x >= sx && x < sx + 24 && y >= sy && y < sy + 24) p = tex[(y - sy) / 2][(x - sx) / 2];
                v[f][y][x] = Math.max(0, Math.min(255, p + NOISE * r.nextGaussian()));
            }
        }
        return v;
    }

    /** Codes one frame block by block. ref == null: I-frame. Otherwise: P-frame with motion search. Returns non-zero coefficients. */
    static long codeFrame(double[][] cur, double[][] ref, int[][] mvOut, double[][] rec, double scale) {
        long nz = 0; int bi = 0;
        for (int by = 0; by < H; by += B) for (int bx = 0; bx < W; bx += B, bi++) {
            double[][] pred = new double[B][B];
            if (ref == null) { for (double[] row : pred) Arrays.fill(row, 128); }   // intra: predict "flat grey"
            else {
                int[] mv = motionSearch(cur, ref, bx, by);                            // "this block = that block, moved"
                mvOut[bi] = mv;
                for (int y = 0; y < B; y++) for (int x = 0; x < B; x++) pred[y][x] = ref[by + y + mv[1]][bx + x + mv[0]];
            }
            double[][] res = new double[B][B];
            for (int y = 0; y < B; y++) for (int x = 0; x < B; x++) res[y][x] = cur[by + y][bx + x] - pred[y][x];
            double[][] coef = dct(res, true);
            for (int u = 0; u < B; u++) for (int w = 0; w < B; w++) {
                double step = Math.max(1, JPEG_LUMA[u * B + w] * scale);
                long level = Math.round(coef[u][w] / step);                          // quantise: THE lossy step
                if (level != 0) nz++;
                coef[u][w] = level * step;                                           // what the decoder will see
            }
            double[][] back = dct(coef, false);
            for (int y = 0; y < B; y++) for (int x = 0; x < B; x++)
                rec[by + y][bx + x] = Math.max(0, Math.min(255, pred[y][x] + back[y][x]));
        }
        return nz;
    }

    /** Full search in a +-7 px window of the previous decoded frame. Cost = sum of absolute differences
     *  + a small charge per pixel of vector length (long vectors cost bits), so flat areas keep (0,0). */
    static int[] motionSearch(double[][] cur, double[][] ref, int bx, int by) {
        int[] best = {0, 0}; double bestSad = Double.MAX_VALUE;
        for (int dy = -SEARCH; dy <= SEARCH; dy++) for (int dx = -SEARCH; dx <= SEARCH; dx++) {
            if (by + dy < 0 || by + dy + B > H || bx + dx < 0 || bx + dx + B > W) continue;
            double s = sad(cur, ref, bx, by, dx, dy) + MV_COST * (Math.abs(dx) + Math.abs(dy));
            if (s < bestSad) { bestSad = s; best = new int[]{dx, dy}; }
        }
        return best;
    }

    static double sad(double[][] cur, double[][] ref, int bx, int by, int dx, int dy) {
        double s = 0;
        for (int y = 0; y < B; y++) for (int x = 0; x < B; x++) s += Math.abs(cur[by + y][bx + x] - ref[by + y + dy][bx + x + dx]);
        return s;
    }

    /** 2-D DCT (forward) or inverse DCT of an 8x8 block: rows, then columns. */
    static double[][] dct(double[][] in, boolean forward) {
        double[][] tmp = new double[B][B], out = new double[B][B];
        for (int i = 0; i < B; i++) for (int k = 0; k < B; k++) for (int n = 0; n < B; n++)
            tmp[i][k] += in[i][n] * (forward ? C[k][n] : C[n][k]);
        for (int k = 0; k < B; k++) for (int j = 0; j < B; j++) for (int n = 0; n < B; n++)
            out[k][j] += tmp[n][j] * (forward ? C[k][n] : C[n][k]);
        return out;
    }

    static void printSomeVectors(int[][] mv) {
        System.out.print("  frame 15 (square's true motion is +3,+1, so it came from (-3,-1)); vectors of blocks near it:");
        int cols = W / B;
        for (int by = 3; by <= 7; by++) {
            System.out.print("\n    row " + by + ": ");
            for (int bx = 5; bx <= 11; bx++) { int[] v = mv[by * cols + bx]; System.out.printf("(%2d,%2d) ", v[0], v[1]); }
        }
        System.out.println();
    }

    static double mse(double[][] a, double[][] b) {
        double s = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) s += (a[y][x] - b[y][x]) * (a[y][x] - b[y][x]);
        return s / (W * H);
    }

    static double psnr(double mse) { return 10 * Math.log10(255.0 * 255.0 / mse); }
}
