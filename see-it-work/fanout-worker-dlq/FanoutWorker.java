import java.util.*;

/** Fan-out worker: one "notify" event -> push/email/SMS jobs -> workers -> flaky providers.
 *  Retries with backoff, DLQ, provider-side dedupe by job key, per-provider circuit breaker.
 *  Simulated clock (ms): nothing really sleeps. Flags: -Djitter=false -Didem=false -DmaxAttempts=N -Dbreaker=false
 *  -Darrival=ms (spread of event arrivals) -DoutageFrom=ms -DoutageTo=ms (email outage) */
public class FanoutWorker {
    static boolean JITTER = !"false".equals(System.getProperty("jitter"));
    static boolean IDEM = !"false".equals(System.getProperty("idem"));
    static boolean BREAKER = !"false".equals(System.getProperty("breaker"));
    static int MAX_ATTEMPTS = Integer.getInteger("maxAttempts", 5);
    static final long BASE = 1000, CAP = 60_000, COOLDOWN = 10_000;
    static final int BREAKER_FAILS = 5, WORKERS = Integer.getInteger("workers", 40);
    enum Out { OK, TIMEOUT, E503, E429, E400 }
    record Res(Out out, long durMs, long retryAfterMs) {}

    static class Job {
        final int id; final String key, channel; final long created;
        int attempts; long readyAt; String lastError = "";
        Job(int id, String key, String channel, long created) {
            this.id = id; this.key = key; this.channel = channel; this.created = created; readyAt = created;
        }
    }
    static class Provider {
        final String name; int consecFails; long openUntil = -1;
        Provider(String n) { name = n; }
    }

    final Random rnd; final boolean chaos, verbose;
    final Map<String, Provider> providers = new HashMap<>();
    final Map<String, ArrayDeque<Res>> script = new HashMap<>();   // scripted outcomes per job key
    final Set<String> seenKeys = new HashSet<>();                  // provider-side dedupe memory
    final int[] sends; final boolean[] done;
    long outageFrom = -1, outageTo = -1;                           // email is down in this window
    int delivered, retries, dupPrevented, deferrals, breakerOpens, dlqButSent;
    final Map<String, Integer> dlq = new TreeMap<>();
    final List<Long> delays = new ArrayList<>();
    final Map<Long, Integer> retryPerSecond = new HashMap<>();

    FanoutWorker(long seed, int jobs, boolean chaos, boolean verbose) {
        rnd = new Random(seed); sends = new int[jobs]; done = new boolean[jobs];
        this.chaos = chaos; this.verbose = verbose;
        for (String c : List.of("push", "email", "sms")) providers.put(c, new Provider(c));
    }

    /** The fake provider. A TIMEOUT may or may not have sent the message (50/50). */
    Res call(Job j, long now) {
        ArrayDeque<Res> s = script.get(j.key);
        Res r = s == null ? randomOutcome(j, now) : s.isEmpty() ? new Res(Out.OK, 80, 0) : s.poll();   // script ends in success
        boolean sends_ = r.out == Out.OK || (r.out == Out.TIMEOUT && r.retryAfterMs == 1);  // 1 = "applied" marker
        if (sends_) {
            if (IDEM && !seenKeys.add(j.key)) dupPrevented++;      // same key again: answer OK, send nothing
            else sends[j.id]++;
        }
        return r;
    }
    Res randomOutcome(Job j, long now) {
        if (j.channel.equals("email") && now >= outageFrom && now < outageTo) return new Res(Out.E503, 30, 0);
        double x = rnd.nextDouble();
        if (x < 0.03) return new Res(Out.TIMEOUT, 2000, rnd.nextBoolean() ? 1 : 0);
        if (x < 0.07) return new Res(Out.E503, 30, 0);
        if (x < 0.10) return new Res(Out.E429, 20, 2000 + rnd.nextInt(3000));
        if (x < 0.11) return new Res(Out.E400, 20, 0);
        return new Res(Out.OK, 80, 0);
    }

    void run(List<Job> all) {
        PriorityQueue<Job> q = new PriorityQueue<>(Comparator.comparingLong((Job j) -> j.readyAt).thenComparingInt(j -> j.id));
        q.addAll(all);
        long[] free = new long[WORKERS];
        while (!q.isEmpty()) {
            Job j = q.poll(); int w = 0;
            for (int i = 1; i < WORKERS; i++) if (free[i] < free[w]) w = i;
            long now = Math.max(free[w], j.readyAt);
            Provider p = providers.get(j.channel);
            if (BREAKER && now < p.openUntil) {                    // breaker open: don't even call, wait for cool-down
                deferrals++; j.readyAt = p.openUntil + (JITTER ? rnd.nextInt(2000) : 0); q.add(j);
                log(now, w, j, "breaker OPEN -> deferred (no attempt used)"); continue;
            }
            j.attempts++;
            Res r = call(j, now); free[w] = now + r.durMs; long end = now + r.durMs;
            if (r.out == Out.OK) {
                p.consecFails = 0; done[j.id] = true; delivered++; delays.add(end - j.created);
                log(now, w, j, "attempt " + j.attempts + " OK"); continue;
            }
            if (r.out == Out.E400) {
                dlq.merge("permanent 400 (invalid token)", 1, Integer::sum); done[j.id] = true;
                log(now, w, j, "400 permanent -> DLQ, no retry"); continue;
            }
            p.consecFails++;                                       // transient failure
            if (BREAKER && p.consecFails >= BREAKER_FAILS) {
                p.openUntil = end + COOLDOWN; breakerOpens++; p.consecFails = BREAKER_FAILS - 1; // half-open: next failure re-opens
                log(now, w, j, p.name + " breaker OPENS until t=" + (p.openUntil / 1000.0) + "s");
            }
            j.lastError = r.out.name().replace("E5", "5").replace("E4", "4").replace("TIMEOUT", "timeout");
            if (j.attempts >= MAX_ATTEMPTS) {
                dlq.merge("exhausted " + MAX_ATTEMPTS + " attempts (last: " + j.lastError + ")", 1, Integer::sum);
                done[j.id] = true; if (sends[j.id] > 0) dlqButSent++;
                log(now, w, j, "attempt " + j.attempts + " " + j.lastError + " -> DLQ"); continue;
            }
            long cap = Math.min(CAP, BASE << (j.attempts - 1));
            long delay = JITTER ? rnd.nextLong(cap + 1) : cap;     // full jitter: uniform in [0, cap]
            if (r.out == Out.E429) delay = Math.max(delay, r.retryAfterMs);   // obey Retry-After
            j.readyAt = end + delay; retries++; retryPerSecond.merge(j.readyAt / 100, 1, Integer::sum);
            q.add(j);
            log(now, w, j, "attempt " + j.attempts + " " + j.lastError + (r.out == Out.TIMEOUT && r.retryAfterMs == 1 ? " (applied!)" : "")
                + " -> retry in " + delay + " ms");
        }
    }
    void log(long t, int w, Job j, String m) {
        if (verbose) System.out.printf("  t=%6.2fs w%02d %-9s %s%n", t / 1000.0, w, j.key, m);
    }

    static Res r(Out o, long d, long ra) { return new Res(o, d, ra); }

    public static void main(String[] a) {
        System.out.printf("config: jitter=%b idempotency=%b breaker=%b maxAttempts=%d%n%n", JITTER, IDEM, BREAKER, MAX_ATTEMPTS);
        // ---- scripted scenario: 2 users x 3 channels ----
        FanoutWorker s = new FanoutWorker(7, 6, false, true);
        String[] keys = {"u1:push", "u1:email", "u1:sms", "u2:push", "u2:email", "u2:sms"};
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) jobs.add(new Job(i, keys[i], keys[i].split(":")[1], 0));
        s.script.put("u1:email", new ArrayDeque<>(List.of(r(Out.E503, 30, 0))));
        s.script.put("u1:sms", new ArrayDeque<>(List.of(r(Out.TIMEOUT, 2000, 1))));          // sent, but reply lost
        s.script.put("u2:push", new ArrayDeque<>(List.of(r(Out.E400, 20, 0))));
        s.script.put("u2:email", new ArrayDeque<>(List.of(r(Out.E429, 20, 5000))));
        s.script.put("u2:sms", new ArrayDeque<>(Collections.nCopies(8, r(Out.E503, 30, 0))));
        System.out.println("1) Scripted: one notify event for u1 and u2 -> 6 channel jobs");
        s.run(jobs);
        System.out.printf("   delivered=%d dlq=%s sends per job=%s duplicates prevented=%d%n%n",
            s.delivered, s.dlq, Arrays.toString(s.sends), s.dupPrevented);

        // ---- chaos run ----
        int users = 3334, n = users * 3;
        FanoutWorker c = new FanoutWorker(42, n, true, false);
        c.outageFrom = Long.getLong("outageFrom", 30_000); c.outageTo = Long.getLong("outageTo", 50_000);
        List<Job> all = new ArrayList<>();
        String[] ch = {"push", "email", "sms"};
        for (int u = 0; u < users; u++) {
            long t = c.rnd.nextInt(Integer.getInteger("arrival", 100_000));   // events arrive over 100 s
            for (int k = 0; k < 3; k++) all.add(new Job(u * 3 + k, "u" + u + ":" + ch[k], ch[k], t));
        }
        System.out.println("2) Chaos: " + n + " jobs, " + WORKERS + " workers, 3% timeouts, 4% 503, 3% 429, 1% 400,"
            + " email provider DOWN " + c.outageFrom / 1000 + "s-" + c.outageTo / 1000 + "s");
        c.run(all);
        Collections.sort(c.delays);
        int dlqTotal = c.dlq.values().stream().mapToInt(Integer::intValue).sum();
        int dup = 0, lost = 0;
        for (int i = 0; i < n; i++) { if (c.sends[i] > 1) dup += c.sends[i] - 1; if (!c.done[i]) lost++; }
        int peak = c.retryPerSecond.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        System.out.printf("   delivered=%d retries=%d dead-lettered=%d%n", c.delivered, c.retries, dlqTotal);
        c.dlq.forEach((k, v) -> System.out.printf("     DLQ %-40s %d%n", k, v));
        System.out.printf("   duplicates prevented by provider dedupe=%d, duplicate messages actually sent=%d%n", c.dupPrevented, dup);
        System.out.printf("   breaker opened %d times, %d jobs deferred without using an attempt%n", c.breakerOpens, c.deferrals);
        System.out.printf("   delivery delay p50=%d ms  p99=%d ms%n", c.delays.get(c.delays.size() / 2), c.delays.get((int) (c.delays.size() * 0.99)));
        System.out.printf("   peak retries scheduled into one 100 ms window=%d%n", peak);
        System.out.printf("   in DLQ although the provider did send it (reply lost)=%d%n", c.dlqButSent);
        boolean ok = c.delivered + dlqTotal == n && lost == 0 && dup == 0;
        System.out.println("   INVARIANT every job delivered exactly once or in DLQ: " + (ok ? "HOLDS" : "VIOLATED")
            + " (delivered " + c.delivered + " + dlq " + dlqTotal + " = " + (c.delivered + dlqTotal) + " of " + n + ", duplicates " + dup + ")");
    }
}
