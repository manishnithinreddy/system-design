import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "How is a voice stored?" in ~150 lines. Synthesises four voice-like vowels
 * ('a' and 'i', each at 120 Hz and 220 Hz), writes them as real 16-bit 16 kHz mono
 * WAV files, reads the numbers back, finds the pitch (autocorrelation), and prints
 * the spectrum: pitch = spacing of the peaks, vowel = shape of the envelope.
 *
 * Run: java VoiceDemo.java          (Java 21, no dependencies)
 *      java VoiceDemo.java --keep   (keep the .wav files so you can play them)
 */
public class VoiceDemo {
    static final int SR = 16_000;           // samples per second
    static final int N = SR / 2;            // 0.5 s of sound = 8,000 samples
    static final int FFT = 4096;            // analysis window: 4,096 samples = 256 ms, 3.9 Hz per bin

    record Vowel(String name, double f1, double f2) {}       // two formants (approximate adult values)
    static final Vowel A = new Vowel("a", 700, 1200), I = new Vowel("i", 300, 2300);

    /** Source-filter: buzz (all harmonics of f0, weaker as they go up) x mouth filter (formant bumps). */
    static short[] synth(double f0, Vowel v) {
        double[] x = new double[N];
        for (int k = 1; k * f0 < 4000; k++) {
            double f = k * f0;
            double source = 1.0 / k;                               // vocal-cord buzz: ~6 dB/octave tilt
            double filter = bump(f, v.f1, 90) + 0.7 * bump(f, v.f2, 120) + 0.02;
            double amp = source * filter, phase = 0.3 * k * k;     // fixed phases, nothing random
            for (int n = 0; n < N; n++) x[n] += amp * Math.sin(2 * Math.PI * f * n / SR + phase);
        }
        double peak = 0;
        for (double s : x) peak = Math.max(peak, Math.abs(s));
        short[] out = new short[N];
        for (int n = 0; n < N; n++) {
            double fade = Math.min(1, Math.min(n, N - 1 - n) / 160.0); // 10 ms fade in/out, no click
            long q = Math.round(x[n] / peak * 0.8 * 32767 * fade);     // quantise to 16-bit integers;
            out[n] = (short) Math.max(-32768, Math.min(32767, q));     // too loud? clip (try 1.5 above)
        }
        return out;
    }
    static double bump(double f, double centre, double width) {   // a resonance: loud near centre
        double d = (f - centre) / width;
        return 1 / (1 + d * d);
    }

    /** A WAV file = 44-byte header + the samples as little-endian 16-bit integers. */
    static byte[] wav(short[] s) {
        ByteBuffer b = ByteBuffer.allocate(44 + 2 * s.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + 2 * s.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1)      // 1 = PCM (plain numbers)
         .putShort((short) 1).putInt(SR).putInt(SR * 2)               // 1 channel, 16000 Hz, bytes/s
         .putShort((short) 2).putShort((short) 16);                   // 2 bytes per frame, 16 bits
        b.put("data".getBytes()).putInt(2 * s.length);
        for (short v : s) b.putShort(v);
        return b.array();
    }
    static short[] readWav(byte[] f) {
        ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
        short[] s = new short[b.getInt(40) / 2];
        b.position(44);
        for (int i = 0; i < s.length; i++) s[i] = b.getShort();
        return s;
    }

    /** Pitch = the shift at which the wave best matches itself (autocorrelation). */
    static double pitch(short[] s) {
        int from = 1000, len = 2048, minLag = SR / 400, maxLag = SR / 60;  // search 60-400 Hz
        double[] r = new double[maxLag + 2];
        double best = 0;
        for (int lag = minLag; lag <= maxLag + 1; lag++) {
            double num = 0, e1 = 0, e2 = 0;
            for (int n = from; n < from + len; n++) {
                num += (double) s[n] * s[n + lag]; e1 += (double) s[n] * s[n]; e2 += (double) s[n + lag] * s[n + lag];
            }
            r[lag] = num / Math.sqrt(e1 * e2);
            best = Math.max(best, r[lag]);
        }
        for (int lag = minLag + 1; lag <= maxLag; lag++)        // FIRST peak close to the best, so we
            if (r[lag] > 0.9 * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) { // don't pick 2x period
                double a = r[lag - 1], b = r[lag], c = r[lag + 1];
                double shift = 0.5 * (a - c) / (a - 2 * b + c);       // parabola through 3 points: sub-sample lag
                return (double) SR / (lag + shift);
            }
        return 0;
    }

    /** Spectrum in dB for 0..4000 Hz: plain DFT ("how much of each sine wave is in here?"). */
    static double[] spectrumDb(short[] s) {
        int bins = 4000 * FFT / SR;
        double[] db = new double[bins];
        for (int k = 0; k < bins; k++) {
            double re = 0, im = 0;
            for (int n = 0; n < FFT; n++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / (FFT - 1));   // Hann window
                double v = s[n + 2000] * w, ang = 2 * Math.PI * k * n / FFT;
                re += v * Math.cos(ang); im -= v * Math.sin(ang);
            }
            db[k] = 20 * Math.log10(Math.hypot(re, im) + 1e-9);
        }
        double max = 0;
        for (double d : db) max = Math.max(max, d);
        for (int k = 0; k < bins; k++) db[k] -= max;   // 0 dB = loudest component
        return db;
    }
    static double hz(int bin) { return bin * (double) SR / FFT; }

    public static void main(String[] args) throws IOException {
        long t0 = System.nanoTime();
        Path dir = Files.createTempDirectory("voice");
        Object[][] cases = {{A, 120.0}, {I, 120.0}, {A, 220.0}, {I, 220.0}};
        double[][] spectra = new double[cases.length][];
        System.out.println("== 1. Write: a voice is just a list of numbers ==");
        for (int c = 0; c < cases.length; c++) {
            Vowel v = (Vowel) cases[c][0]; double f0 = (double) cases[c][1];
            Path p = dir.resolve(v.name + "-" + (int) f0 + "Hz.wav");
            Files.write(p, wav(synth(f0, v)));
            byte[] file = Files.readAllBytes(p);
            short[] s = readWav(file);
            if (c == 0) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 44; i++) hex.append(String.format("%02X%s", file[i], i % 22 == 21 ? "\n  " : " "));
                System.out.println("header of " + p.getFileName() + " (44 bytes):\n  " + hex.toString().trim());
                System.out.println("  = 'RIFF' size 'WAVE' 'fmt ' 16, PCM=1, channels=1, 16000 Hz, 32000 bytes/s, 2, 16 bits, 'data' 16000");
                StringBuilder first = new StringBuilder();
                for (int i = 1000; i < 1012; i++) first.append(s[i]).append(' ');
                System.out.println("samples 1000..1011 (one every 1/16000 s): " + first);
            }
            int peak = 0;
            for (short x : s) peak = Math.max(peak, Math.abs(x));
            System.out.printf("%-14s %,6d bytes = 44 header + %,d samples x 2 bytes | loudest sample %,d of 32,767 | pitch found %.1f Hz (true %.0f)%n",
                    p.getFileName(), Files.size(p), s.length, peak, pitch(s), f0);
            spectra[c] = spectrumDb(s);
        }
        System.out.println("\n== 2. Strongest peaks in each spectrum (Hz, dB below loudest) ==");
        for (int c = 0; c < cases.length; c++) {
            double[] d = spectra[c];
            java.util.List<int[]> peaks = new java.util.ArrayList<>();
            for (int k = 2; k < d.length - 1; k++)
                if (d[k] > d[k - 1] && d[k] >= d[k + 1] && d[k] > -60) peaks.add(new int[]{k});
            peaks.sort((x, y) -> Double.compare(d[y[0]], d[x[0]]));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, peaks.size()); i++) {
                int k = peaks.get(i)[0];
                sb.append(String.format("%5.0f (%3.0f)  ", hz(k), d[k]));
            }
            System.out.printf("'%s' @ %3.0f Hz: %s%n", ((Vowel) cases[c][0]).name, (double) cases[c][1], sb);
        }
        System.out.println("\n== 3. Spectrum, 0-3000 Hz in 125 Hz rows (bar = loudest harmonic in the row) ==");
        System.out.println("   Hz  | 'a' @120 Hz      | 'i' @120 Hz      | 'a' @220 Hz      | 'i' @220 Hz");
        for (int row = 0; row < 24; row++) {
            StringBuilder line = new StringBuilder(String.format("%5d  ", row * 125));
            for (double[] d : spectra) {
                double m = -99;
                for (int k = (int) (row * 125 * FFT / SR); k < (int) ((row + 1) * 125 * FFT / SR); k++) m = Math.max(m, d[k]);
                int len = (int) Math.max(0, Math.round((m + 48) / 3));   // 3 dB per '#', floor -48 dB
                line.append("| ").append("#".repeat(len)).append(" ".repeat(17 - len));
            }
            System.out.println(line.toString().stripTrailing());
        }
        boolean keep = args.length > 0 && args[0].equals("--keep");
        if (keep) System.out.println("\nkept: " + dir + "  (play with: aplay / afplay / VLC)");
        else try (var files = Files.list(dir)) { for (Path f : files.toList()) Files.delete(f); Files.delete(dir); }
        System.out.printf("%ndone in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }
}
