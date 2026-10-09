import java.util.Random;

/**
 * Upscaling vs super-resolution on a tiny synthetic grayscale image.
 * Part 1: make a sharp 64x64 image, shrink it 4x (average 4x4 blocks) to 16x16,
 *         blow it back up with nearest, bilinear and bicubic, score each with PSNR.
 * Part 2: "camera shake": several 16x16 frames of the same scene, each shifted by a
 *         fraction of a low-res pixel. Combine them on the 64x64 grid (shift-and-add,
 *         then iterative back-projection) and score again.
 *
 * Run: java UpscaleDemo.java      (Java 21, no dependencies)
 */
public class UpscaleDemo {
    static final int N = 64, F = 4, L = N / F;          // high-res size, scale factor, low-res size

    // ---------- the "real scene" ----------
    static double[][] makeScene() {
        double[][] img = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) img[y][x] = 40;     // dark background
        for (int y = 6; y < 26; y++) for (int x = 38; x < 58; x++) img[y][x] = 220;  // bright square
        for (int t = 0; t < 60; t++) {                                                 // thin diagonal line
            int x = 2 + t / 2, y = 2 + t; if (y < N && x < N) { img[y][x] = 255; img[y][x + 1] = 255; }
        }
        String[] glyphs = {"X.X.XXX.X..", "X.X..X..X..", "XXX..X..X..", "X.X..X.....", "X.X.XXX.X.."}; // "HI!"
        for (int r = 0; r < glyphs.length; r++)
            for (int c = 0; c < glyphs[r].length(); c++)
                if (glyphs[r].charAt(c) == 'X')                        // each glyph pixel = 3x3 block
                    for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) img[38 + r * 3 + dy][24 + c * 3 + dx] = 250;
        return img;
    }

    static double px(double[][] img, int y, int x) {            // clamp at the borders
        int h = img.length, w = img[0].length;
        return img[Math.max(0, Math.min(h - 1, y))][Math.max(0, Math.min(w - 1, x))];
    }

    /** Camera: average each 4x4 block, with the sensor shifted by (sx, sy) high-res pixels. */
    static double[][] capture(double[][] hi, int sx, int sy) {
        double[][] lo = new double[L][L];
        for (int j = 0; j < L; j++) for (int i = 0; i < L; i++) {
            double s = 0;
            for (int a = 0; a < F; a++) for (int b = 0; b < F; b++) s += px(hi, F * j + sy + a, F * i + sx + b);
            lo[j][i] = Math.round(s / (F * F));                     // stored as whole numbers, like 8-bit video
        }
        return lo;
    }

    // ---------- classic interpolation ----------
    static double cubic(double t) {                              // Keys cubic kernel, a = -0.5
        t = Math.abs(t);
        if (t < 1) return 1.5 * t * t * t - 2.5 * t * t + 1;
        if (t < 2) return -0.5 * t * t * t + 2.5 * t * t - 4 * t + 2;
        return 0;
    }

    /** Upscale one low-res frame; (sx, sy) is where that frame's sensor sat. kind: 0 nearest, 1 bilinear, 2 bicubic */
    static double[][] upscale(double[][] lo, int kind, int sx, int sy) {
        double[][] hi = new double[N][N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double u = (x - sx - (F - 1) / 2.0) / F, v = (y - sy - (F - 1) / 2.0) / F; // position in low-res pixels
            int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
            double fu = u - iu, fv = v - iv, val = 0;
            if (kind == 0) val = px(lo, (int) Math.round(v), (int) Math.round(u));
            else if (kind == 1) val = (1 - fv) * ((1 - fu) * px(lo, iv, iu) + fu * px(lo, iv, iu + 1))
                                    + fv * ((1 - fu) * px(lo, iv + 1, iu) + fu * px(lo, iv + 1, iu + 1));
            else for (int m = -1; m <= 2; m++) for (int n = -1; n <= 2; n++)
                val += cubic(m - fv) * cubic(n - fu) * px(lo, iv + m, iu + n);
            hi[y][x] = Math.max(0, Math.min(255, val));
        }
        return hi;
    }

    // ---------- multi-frame super-resolution ----------
    /** Shift-and-add: put every frame back where it was captured, then average. Then iterative back-projection. */
    static double[][] multiFrame(double[][][] frames, int[][] shifts, int iterations) {
        int k = frames.length;
        double[][] est = new double[N][N];
        for (int f = 0; f < k; f++) {
            double[][] up = upscale(frames[f], 1, shifts[f][0], shifts[f][1]);
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) est[y][x] += up[y][x] / k;
        }
        for (int it = 0; it < iterations; it++) {                 // "if my guess were the truth, would the camera
            double[][] corr = new double[N][N];                   //  have seen these frames? fix the difference"
            for (int f = 0; f < k; f++) {
                double[][] sim = capture(est, shifts[f][0], shifts[f][1]);
                for (int j = 0; j < L; j++) for (int i = 0; i < L; i++) {
                    double err = frames[f][j][i] - sim[j][i];
                    for (int a = 0; a < F; a++) for (int b = 0; b < F; b++) {
                        int y = F * j + shifts[f][1] + a, x = F * i + shifts[f][0] + b;
                        if (y >= 0 && y < N && x >= 0 && x < N) corr[y][x] += err / k;
                    }
                }
            }
            for (int y = 0; y < N; y++) for (int x = 0; x < N; x++)
                est[y][x] = Math.max(0, Math.min(255, est[y][x] + corr[y][x]));
        }
        return est;
    }

    // ---------- scoring and printing ----------
    static double psnr(double[][] a, double[][] b) {
        double mse = 0;
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) mse += (a[y][x] - b[y][x]) * (a[y][x] - b[y][x]);
        mse /= N * N;
        return 10 * Math.log10(255.0 * 255.0 / mse);
    }

    static void show(String[] titles, double[][]... imgs) {      // crop rows 36..55, cols 22..57 (the "HI!" text)
        String ramp = " .:-=+*#%@";
        StringBuilder sb = new StringBuilder();
        for (String t : titles) sb.append(String.format("%-38s", t));
        System.out.println(sb.toString().stripTrailing());
        for (int y = 36; y < 56; y++) {
            sb.setLength(0);
            for (double[][] img : imgs) {
                for (int x = 22; x < 58; x++) sb.append(ramp.charAt((int) Math.min(9, img[y][x] / 25.6)));
                sb.append("  ");
            }
            System.out.println(sb.toString().stripTrailing());
        }
        System.out.println();
    }

    public static void main(String[] args) {
        double[][] hi = makeScene();
        double[][] lo = capture(hi, 0, 0);
        System.out.printf("== Part 1: one 16x16 frame -> 64x64 (%d pixels known, %d to fill) ==%n", L * L, N * N);
        double[][] near = upscale(lo, 0, 0, 0), bil = upscale(lo, 1, 0, 0), bic = upscale(lo, 2, 0, 0);
        System.out.printf("nearest  PSNR %.2f dB%nbilinear PSNR %.2f dB%nbicubic  PSNR %.2f dB%n%n",
                psnr(hi, near), psnr(hi, bil), psnr(hi, bic));
        show(new String[]{"original (truth)", "nearest (blocky)"}, hi, near);
        show(new String[]{"bilinear (soft)", "bicubic (soft, slightly crisper)"}, bil, bic);

        System.out.println("== Part 2: several 16x16 frames of the same scene ==");
        Random r = new Random(7);
        int[][] all = new int[16][];
        for (int s = 0; s < 16; s++) all[s] = new int[]{s % 4, s / 4};               // every 1/4-pixel offset
        for (int i = 15; i > 0; i--) { int j = 1 + r.nextInt(i); int[] t = all[i]; all[i] = all[j]; all[j] = t; } // keep (0,0) first
        for (int k : new int[]{1, 2, 4, 8, 16}) {
            int[][] sh = java.util.Arrays.copyOf(all, k);
            double[][][] fr = new double[k][][];
            for (int f = 0; f < k; f++) fr[f] = capture(hi, sh[f][0], sh[f][1]);
            System.out.printf("%2d shaken frames: shift-and-add %.2f dB, + back-projection %.2f dB%n",
                    k, psnr(hi, multiFrame(fr, sh, 0)), psnr(hi, multiFrame(fr, sh, 30)));
        }
        int[][] same = new int[16][];
        double[][][] fr = new double[16][][];
        for (int f = 0; f < 16; f++) { same[f] = new int[]{0, 0}; fr[f] = capture(hi, 0, 0); }
        System.out.printf("16 frames, tripod (no shift): shift-and-add %.2f dB, + back-projection %.2f dB%n%n",
                psnr(hi, multiFrame(fr, same, 0)), psnr(hi, multiFrame(fr, same, 30)));

        double[][][] fr16 = new double[16][][];
        for (int f = 0; f < 16; f++) fr16[f] = capture(hi, all[f][0], all[f][1]);
        show(new String[]{"bicubic from 1 frame", "16 shaken frames + back-projection"}, bic, multiFrame(fr16, all, 30));
    }
}
