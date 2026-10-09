import java.util.*;

/**
 * Static vs continuous batching on a simulated GPU (simulated time only, nothing real runs).
 * Cost model (illustrative numbers): one decode step = STEP_BASE_MS (reading the weights, same for 1 or 16
 * sequences) + PER_SEQ_MS per sequence in the batch. Admitting a request costs PREFILL_MS once.
 * The batch is capped at MAX_BATCH sequences (a stand-in for "KV cache memory is full").
 * Run: java BatchingSim.java
 */
public class BatchingSim {
    static final double STEP_BASE_MS = 25, PER_SEQ_MS = 0.5, PREFILL_MS = 20;
    static final int MAX_BATCH = 16, N = 400;
    static final double MEAN_GAP_MS = 400;           // average time between arrivals

    static final class Req {
        final int id, outTokens; final double arrival;
        int done; double firstToken = -1, finish = -1;
        Req(int id, double arrival, int outTokens) { this.id = id; this.arrival = arrival; this.outTokens = outTokens; }
    }

    static List<Req> makeRequests(long seed) {
        Random r = new Random(seed);
        List<Req> list = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < N; i++) {
            t += -Math.log(1 - r.nextDouble()) * MEAN_GAP_MS;               // random (Poisson) arrivals
            int len = 10 + (int) (-Math.log(1 - r.nextDouble()) * 120);      // many short, a few very long answers
            list.add(new Req(i, t, Math.min(len, 600)));
        }
        return list;
    }

    /** Static: fill a batch, run it until EVERY sequence finishes, only then take new requests. */
    static void runStatic(List<Req> reqs) {
        double now = 0; int next = 0;
        while (next < reqs.size()) {
            now = Math.max(now, reqs.get(next).arrival);                     // idle until someone arrives
            List<Req> batch = new ArrayList<>();
            while (next < reqs.size() && batch.size() < MAX_BATCH && reqs.get(next).arrival <= now) batch.add(reqs.get(next++));
            now += PREFILL_MS * batch.size();
            int steps = batch.stream().mapToInt(q -> q.outTokens).max().getAsInt();
            for (int s = 1; s <= steps; s++) {
                now += STEP_BASE_MS + PER_SEQ_MS * batch.size();             // finished sequences still occupy their slot
                for (Req q : batch) {
                    if (q.done < q.outTokens) { q.done++; if (q.done == 1) q.firstToken = now; if (q.done == q.outTokens) q.finish = now; }
                }
            }
        }
    }

    /** Continuous: every step, finished sequences leave and waiting ones join immediately. */
    static void runContinuous(List<Req> reqs) {
        double now = 0; int next = 0;
        List<Req> active = new ArrayList<>();
        while (next < reqs.size() || !active.isEmpty()) {
            if (active.isEmpty()) now = Math.max(now, reqs.get(next).arrival);
            while (next < reqs.size() && active.size() < MAX_BATCH && reqs.get(next).arrival <= now) {
                active.add(reqs.get(next++)); now += PREFILL_MS;             // prefill the newcomer
            }
            now += STEP_BASE_MS + PER_SEQ_MS * active.size();
            for (Iterator<Req> it = active.iterator(); it.hasNext(); ) {
                Req q = it.next(); q.done++;
                if (q.done == 1) q.firstToken = now;
                if (q.done == q.outTokens) { q.finish = now; it.remove(); }
            }
        }
    }

    static void report(String name, List<Req> reqs) {
        double end = reqs.stream().mapToDouble(q -> q.finish).max().getAsDouble();
        long tokens = reqs.stream().mapToLong(q -> q.outTokens).sum();
        double ttft = reqs.stream().mapToDouble(q -> q.firstToken - q.arrival).average().getAsDouble();
        double[] lat = reqs.stream().mapToDouble(q -> q.finish - q.arrival).sorted().toArray();
        double[] tt = reqs.stream().mapToDouble(q -> q.firstToken - q.arrival).sorted().toArray();
        System.out.printf("%-11s throughput %6.0f tok/s | avg TTFT %7.0f ms | p99 TTFT %7.0f ms | p99 latency %8.0f ms%n",
                name, tokens / (end / 1000), ttft, tt[(int) (N * 0.99) - 1], lat[(int) (N * 0.99) - 1]);
    }

    public static void main(String[] args) {
        System.out.printf("%d requests, avg gap %.0f ms, batch cap %d, step = %.0f ms + %.1f ms/seq%n",
                N, MEAN_GAP_MS, MAX_BATCH, STEP_BASE_MS, PER_SEQ_MS);
        List<Req> a = makeRequests(42), b = makeRequests(42);                 // identical workload for both
        runStatic(a);     report("static", a);
        runContinuous(b); report("continuous", b);
    }
}
