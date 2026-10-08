import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Simulates a video player choosing a quality for each 4-second segment while the
 * network changes underneath it. Four ABR (adaptive bitrate) strategies are compared
 * on one scripted network trace, then on 200 random traces.
 *
 * Run: java AbrSimulator.java      (Java 21, no dependencies)
 */
public class AbrSimulator {

    static final int[] LADDER_KBPS = {400, 800, 1400, 3000, 5000}; // printed as 1..5
    static final double SEG_SEC = 4.0;          // each segment holds 4 s of video
    static final int SEGMENTS = 45;             // 45 x 4 s = a 3-minute video
    static final double MAX_BUFFER_SEC = 30.0;  // player stops downloading when 30 s are buffered
    static final double RTT_SEC = 0.08;         // request round trip before bytes start flowing

    /** Network capacity in kbps for each second of wall-clock time. */
    static int[] scriptedTrace() {
        List<Integer> t = new ArrayList<>();
        for (int s = 0; s < 20; s++) t.add(8000);                     // good wifi
        for (int s = 0; s < 40; s++) t.add(1000);                     // lift / tunnel
        for (int s = 0; s < 40; s++) t.add(s % 10 < 4 ? 9000 : 300);  // bursty: 4 s fast, 6 s nearly dead
        for (int s = 0; s < 300; s++) t.add(6000);                    // recovered
        return t.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Random trace: hold a random capacity for 5-30 s, then jump to another one. */
    static int[] randomTrace(Random r) {
        int[] levels = {300, 800, 1500, 3000, 6000, 10000};
        int[] t = new int[400];
        for (int i = 0; i < t.length; ) {
            int len = 5 + r.nextInt(26), kbps = levels[r.nextInt(levels.length)];
            for (int k = 0; k < len && i < t.length; k++) t[i++] = kbps;
        }
        return t;
    }

    /** Wall-clock time at which a download of `kbits` started at `start` finishes. */
    static double download(int[] trace, double start, double kbits) {
        double t = start + RTT_SEC, remaining = kbits;
        while (true) {
            double cap = trace[Math.min((int) t, trace.length - 1)];
            double canSend = cap * (Math.floor(t) + 1 - t);   // until the next whole second
            if (canSend >= remaining) return t + remaining / cap;
            remaining -= canSend;
            t = Math.floor(t) + 1;
        }
    }

    static int highestBelow(double kbps) {
        int q = 0;
        for (int i = 0; i < LADDER_KBPS.length; i++) if (LADDER_KBPS[i] <= kbps) q = i;
        return q;
    }

    interface Strategy {
        int pick(double bufferSec, int currentQ);
        default void observe(double measuredKbps) {}
    }

    /** (a) Trust the last segment's measured throughput completely. */
    static class Naive implements Strategy {
        double last = -1;
        public int pick(double buf, int cur) { return last < 0 ? 0 : highestBelow(last); }
        public void observe(double kbps) { last = kbps; }
    }

    /** (b) Smooth the measurements with one EWMA and use only 80% of the estimate. */
    static class Ewma implements Strategy {
        double est = -1;
        public int pick(double buf, int cur) { return est < 0 ? 0 : highestBelow(est * 0.8); }
        public void observe(double kbps) { est = est < 0 ? kbps : 0.3 * kbps + 0.7 * est; }
    }

    /** (c) hls.js / Shaka idea: a fast and a slow EWMA, trust the lower one, x 0.8. */
    static class DualEwma implements Strategy {
        double fast = -1, slow = -1;
        public int pick(double buf, int cur) { return fast < 0 ? 0 : highestBelow(Math.min(fast, slow) * 0.8); }
        public void observe(double kbps) {
            fast = fast < 0 ? kbps : 0.6 * kbps + 0.4 * fast;
            slow = slow < 0 ? kbps : 0.15 * kbps + 0.85 * slow;
        }
    }

    /** (d) BBA-0 style: buffer level alone picks the rate, reservoir 8 s, cushion 8-26 s. */
    static class BufferBased implements Strategy {
        static final double RESERVOIR = 8, CUSHION = 18;
        public int pick(double buf, int cur) {
            if (buf <= RESERVOIR) return 0;                         // danger zone: lowest rung
            int min = LADDER_KBPS[0], max = LADDER_KBPS[LADDER_KBPS.length - 1];
            double f = Math.min(max, min + (buf - RESERVOIR) / CUSHION * (max - min));
            // Hysteresis from the BBA-0 paper: stay put unless f crosses a neighbouring rung.
            if (cur < LADDER_KBPS.length - 1 && f >= LADDER_KBPS[cur + 1]) return highestBelow(f);
            if (cur > 0 && f <= LADDER_KBPS[cur - 1]) {             // step down to the lowest rung above f
                int q = 0;
                while (LADDER_KBPS[q] <= f) q++;
                return q;
            }
            return cur;
        }
    }

    record Result(double avgKbps, int switches, double stallSec, double startupSec, String qualities, String stalls) {}

    static Result play(Strategy s, int[] trace, boolean abandon) {
        double t = 0, buffer = 0, stallSec = 0, startup = -1, sumKbps = 0;
        int q = 0, switches = 0;
        StringBuilder qualities = new StringBuilder(), stalls = new StringBuilder();
        for (int i = 0; i < SEGMENTS; i++) {
            int next = s.pick(buffer, q);
            if (i > 0 && next != q) switches++;
            q = next;
            if (buffer + SEG_SEC > MAX_BUFFER_SEC) {     // buffer full: idle while playback continues
                double wait = buffer + SEG_SEC - MAX_BUFFER_SEC;
                t += wait;
                buffer -= wait;
            }
            double kbits = LADDER_KBPS[q] * SEG_SEC;
            double done = download(trace, t, kbits), dt = done - t;
            boolean stalled = false;
            if (abandon && startup >= 0 && q > 0 && dt > buffer) {
                // Too slow: the buffer will run dry first. Give up after half the buffer is gone and
                // refetch at the lowest rung. (Real players project this from download progress; we peek.)
                double elapsed = Math.min(dt, Math.max(0.5, buffer / 2));
                if (elapsed > buffer) { stallSec += elapsed - buffer; buffer = 0; stalled = true; }
                else buffer -= elapsed;
                t += elapsed;
                q = 0;
                switches++;
                kbits = LADDER_KBPS[q] * SEG_SEC;
                done = download(trace, t, kbits);
                dt = done - t;
            }
            if (startup >= 0) {                           // playing: the buffer drains while we download
                if (buffer >= dt) buffer -= dt;
                else { stallSec += dt - buffer; buffer = 0; stalled = true; }
            }
            t = done;
            buffer += SEG_SEC;
            if (startup < 0) startup = t;                 // first frame once the first segment is in
            s.observe(kbits / dt);
            sumKbps += LADDER_KBPS[q];
            qualities.append(q + 1);
            stalls.append(stalled ? '^' : ' ');
        }
        return new Result(sumKbps / SEGMENTS, switches, stallSec, startup,
                qualities.toString(), stalls.toString().stripTrailing());
    }

    public static void main(String[] args) {
        List<String> names = List.of("(a) naive: last throughput", "(b) EWMA x 0.8",
                "(c) min(fast, slow EWMA) x 0.8", "(d) buffer-based (BBA-0 style)");
        List<Supplier<Strategy>> makers = List.of(Naive::new, Ewma::new, DualEwma::new, BufferBased::new);

        System.out.println("ladder: 1=240p@400  2=360p@800  3=480p@1400  4=720p@3000  5=1080p@5000 kbps");
        System.out.println("network: 0-20 s 8 Mbps | 20-60 s 1 Mbps | 60-100 s bursty (4 s at 9 Mbps, 6 s at 0.3) | then 6 Mbps");
        System.out.println("one digit per 4 s segment, ^ = playback stalled while that segment downloaded\n");
        int[] trace = scriptedTrace();
        for (int i = 0; i < names.size(); i++) {
            Result r = play(makers.get(i).get(), trace, false);
            System.out.printf("%-32s avg %,5.0f kbps | switches %2d | stalls %4.1f s | startup %.2f s%n",
                    names.get(i), r.avgKbps(), r.switches(), r.stallSec(), r.startupSec());
            System.out.println("  quality " + r.qualities());
            System.out.println("  stall   " + r.stalls() + "\n");
        }

        int runs = 200;
        for (boolean abandon : new boolean[] {false, true}) {
            System.out.println(runs + " random traces (capacity jumps every 5-30 s between 0.3 and 10 Mbps)"
                    + (abandon ? ", WITH abandoning too-slow downloads:" : ":"));
            for (int i = 0; i < names.size(); i++) {
                Random rnd = new Random(42);              // same traces for every strategy
                double kbps = 0, sw = 0, stall = 0;
                int sessionsWithStall = 0;
                for (int k = 0; k < runs; k++) {
                    Result r = play(makers.get(i).get(), randomTrace(rnd), abandon);
                    kbps += r.avgKbps(); sw += r.switches(); stall += r.stallSec();
                    if (r.stallSec() > 0) sessionsWithStall++;
                }
                System.out.printf("%-32s avg %,5.0f kbps | switches %4.1f | stalls %4.1f s | sessions with a stall %3d%%%n",
                        names.get(i), kbps / runs, sw / runs, stall / runs, 100 * sessionsWithStall / runs);
            }
            System.out.println();
        }
    }
}
