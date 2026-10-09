import java.util.*;

/** Mini Shazam: fake "songs" -> peaks -> hashes -> index -> offset voting. Run: java FingerprintDemo.java */
public class FingerprintDemo {
    static final int RATE = 8000, N = 512, HOP = 256;               // frame = 64 ms, step = 32 ms
    static final int[] BAND = {5, 12, 24, 48, 120};                  // FFT-bin band edges (~78 Hz per bin)

    public static void main(String[] a) {
        Map<Long, List<int[]>> index = new HashMap<>();              // hash -> [songId, anchorFrame]
        double[][] songs = new double[20][];
        for (int s = 0; s < 20; s++) {
            songs[s] = synth(new Random(100 + s), 30);               // 30 s each
            for (long[] h : fingerprint(songs[s])) index.computeIfAbsent(h[0], k -> new ArrayList<>()).add(new int[]{s, (int) h[1]});
        }
        int entries = index.values().stream().mapToInt(List::size).sum();
        System.out.printf("indexed 20 songs x 30 s: %,d hashes stored (%,d distinct keys)%n", entries, index.size());

        int target = 7; double startSec = 12.3, noise = 2.0;
        double[] clip = Arrays.copyOfRange(songs[target], (int) (startSec * RATE), (int) ((startSec + 3) * RATE));
        Random r = new Random(1); double sig = 0;
        for (double v : clip) sig += v * v;
        double sd = Math.sqrt(sig / clip.length) * noise;            // noise louder than the music
        for (int i = 0; i < clip.length; i++) clip[i] += r.nextGaussian() * sd;
        System.out.printf("query: 3 s of song %d from %.1f s, noise %.0fx the music's loudness (SNR %.0f dB)%n", target, startSec, noise, -20 * Math.log10(noise));

        List<long[]> q = fingerprint(clip);
        Map<String, Integer> votes = new HashMap<>();
        int hits = 0;
        for (long[] h : q)
            for (int[] e : index.getOrDefault(h[0], List.of())) { hits++; votes.merge(e[0] + "@" + (e[1] - (int) h[1]), 1, Integer::sum); }
        System.out.printf("query hashes: %d, index matches: %d (mostly coincidences)%n", q.size(), hits);
        votes.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3).forEach(e -> {
            String[] p = e.getKey().split("@");
            System.out.printf("  song %s, offset %.2f s -> %d votes%n", p[0], Integer.parseInt(p[1]) * HOP / (double) RATE, e.getValue());
        });
        votes.entrySet().stream().filter(e -> !e.getKey().startsWith(target + "@")).max(Map.Entry.comparingByValue())
            .ifPresent(e -> System.out.println("  best wrong answer: song " + e.getKey().replace("@", ", frame offset ") + " -> " + e.getValue() + " votes"));
    }

    /** A "song": every 0.25 s a new chord of 3 random notes (200-3500 Hz). */
    static double[] synth(Random r, int sec) {
        double[] x = new double[sec * RATE];
        for (int t = 0; t < x.length; t += RATE / 4) {
            double[] f = {200 + r.nextInt(3300), 200 + r.nextInt(3300), 200 + r.nextInt(3300)};
            for (int i = t; i < Math.min(x.length, t + RATE / 4); i++) for (double fr : f) x[i] += Math.sin(2 * Math.PI * fr * i / RATE);
        }
        return x;
    }

    /** Spectrogram -> strongest bin per band per frame -> hash(f1, f2, dt) of nearby peak pairs. */
    static List<long[]> fingerprint(double[] x) {
        List<int[]> peaks = new ArrayList<>();                       // [frame, bin]
        for (int f = 0; f + N <= x.length; f += HOP) {
            double[] re = new double[N], im = new double[N];
            for (int i = 0; i < N; i++) re[i] = x[f + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));   // Hann window
            fft(re, im);
            for (int b = 0; b + 1 < BAND.length; b++) {
                int best = BAND[b]; double m = 0;
                for (int k = BAND[b]; k < BAND[b + 1]; k++) { double p = re[k] * re[k] + im[k] * im[k]; if (p > m) { m = p; best = k; } }
                peaks.add(new int[]{f / HOP, best});
            }
        }
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < peaks.size(); i++)
            for (int j = i + 1, c = 0; j < peaks.size() && c < 5; j++) {
                int dt = peaks.get(j)[0] - peaks.get(i)[0];
                if (dt < 1) continue; if (dt > 16) break; c++;
                out.add(new long[]{((long) peaks.get(i)[1] << 20) | ((long) peaks.get(j)[1] << 8) | dt, peaks.get(i)[0]});
            }
        return out;
    }

    static void fft(double[] re, double[] im) {                      // iterative radix-2
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            for (int i = 0; i < n; i += len) for (int k = 0; k < len / 2; k++) {
                double wr = Math.cos(ang * k), wi = Math.sin(ang * k);
                int u = i + k, v = i + k + len / 2;
                double xr = re[v] * wr - im[v] * wi, xi = re[v] * wi + im[v] * wr;
                re[v] = re[u] - xr; im[v] = im[u] - xi; re[u] += xr; im[u] += xi;
            }
        }
    }
}
