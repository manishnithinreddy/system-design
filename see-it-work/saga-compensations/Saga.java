import java.util.*;

/** Order saga: reserve stock → charge payment → create shipment, with retries, compensations and crash recovery. */
public class Saga {
    static class Transient extends RuntimeException { Transient(String m) { super(m); } }
    static class Permanent extends RuntimeException { Permanent(String m) { super(m); } }
    static class Crash extends RuntimeException { Crash(String m) { super(m); } }

    static boolean quiet = false;
    static int retries = 0, duplicates = 0, crashes = 0;
    static void say(String s) { if (!quiet) System.out.println(s); }

    /** Scripted or random faults, keyed by "op:order". */
    static class Faults {
        Map<String, Integer> timeoutBefore = new HashMap<>(), timeoutAfter = new HashMap<>();
        Set<String> permanent = new HashSet<>(), crashAfter = new HashSet<>();
        Random rnd; double pTimeout, pCrash;

        boolean take(Map<String, Integer> m, String k) {
            int n = m.getOrDefault(k, 0);
            if (n > 0) m.put(k, n - 1);
            return n > 0;
        }
        void before(String k) {
            if (permanent.contains(k)) throw new Permanent(k + " rejected");
            if (take(timeoutBefore, k) || (rnd != null && rnd.nextDouble() < pTimeout / 2)) throw new Transient(k + " timed out (not applied)");
        }
        void after(String k) {   // the work happened, but the caller never hears back
            if (take(timeoutAfter, k) || (rnd != null && rnd.nextDouble() < pTimeout / 2)) throw new Transient(k + " timed out (applied!)");
        }
        void crashPoint(String k) {
            if (crashAfter.remove(k) || (rnd != null && rnd.nextDouble() < pCrash)) throw new Crash("orchestrator died after " + k);
        }
    }
    static Faults faults = new Faults();

    /** A downstream service. Idempotent: the order ID is the idempotency key for both do and undo. */
    static class Service {
        final String doOp, undoOp, doText, undoText;
        final Map<String, Boolean> applied = new HashMap<>();   // order → effect currently in place?
        int doEffects = 0, undoEffects = 0;
        Service(String doOp, String undoOp, String doText, String undoText) {
            this.doOp = doOp; this.undoOp = undoOp; this.doText = doText; this.undoText = undoText;
        }
        void doIt(String order) {
            faults.before(doOp + ":" + order);
            Boolean state = applied.get(order);
            if (state == null) { applied.put(order, true); doEffects++; say("      " + doText + " for " + order); }
            else if (state) { duplicates++; say("      " + doOp + " " + order + ": same key seen before, returning stored result (no second effect)"); }
            else throw new Permanent(doOp + " " + order + " refused: already compensated");
            faults.after(doOp + ":" + order);
        }
        void undo(String order) {
            faults.before(undoOp + ":" + order);
            Boolean state = applied.get(order);
            if (Boolean.TRUE.equals(state)) { applied.put(order, false); undoEffects++; say("      " + undoText + " for " + order); }
            else if (state == null) { applied.put(order, false); say("      " + undoOp + " " + order + ": nothing was applied, recorded as undone"); }
            else { duplicates++; say("      " + undoOp + " " + order + ": already undone (no second effect)"); }
            faults.after(undoOp + ":" + order);
        }
    }

    static final Service INVENTORY = new Service("reserve", "release", "inventory: held 1 pair of shoes", "inventory: released the hold");
    static final Service PAYMENT = new Service("charge", "refund", "payment: charged ₹2,499", "payment: refunded ₹2,499");
    static final Service SHIPPING = new Service("ship", "cancel", "shipping: created shipment", "shipping: cancelled shipment");
    static final List<Service> STEPS = List.of(INVENTORY, PAYMENT, SHIPPING);

    /** The durable step log. Stands for a saga_steps table: it survives orchestrator crashes. */
    static final Map<String, List<String>> LOG = new LinkedHashMap<>();
    static void record(String order, String entry) {
        LOG.computeIfAbsent(order, k -> new ArrayList<>()).add(entry);
        say("    log[" + order + "] += " + entry);
    }

    static void withRetry(Runnable call, int maxAttempts) {
        for (int attempt = 1; ; attempt++) {
            try { call.run(); return; }
            catch (Transient e) {
                if (attempt == maxAttempts) throw e;
                retries++;
                say("      ! " + e.getMessage() + ", retry " + attempt + " after " + Math.min(100L << Math.min(attempt - 1, 20), 60_000) + " ms (same key)");
            }
        }
    }

    /** Runs (or resumes) one order's saga using only what the log says. */
    static void run(String order) {
        List<String> log = LOG.computeIfAbsent(order, k -> new ArrayList<>());
        if (log.contains("COMPLETED") || log.contains("COMPENSATED")) return;
        if (!log.isEmpty()) say("    resuming " + order + " from log " + log);
        int failedAt = log.stream().filter(e -> e.startsWith("COMPENSATING")).map(e -> Integer.parseInt(e.substring(e.indexOf('@') + 1).split(" ")[0])).findFirst().orElse(-1);
        if (failedAt < 0) {
            for (int i = 0; i < STEPS.size() && failedAt < 0; i++) {
                Service s = STEPS.get(i);
                if (log.contains("done:" + s.doOp)) continue;
                try { withRetry(() -> s.doIt(order), 4); }
                catch (Transient | Permanent e) { record(order, "COMPENSATING@" + i + " (" + e.getMessage() + ")"); failedAt = i; break; }
                faults.crashPoint(s.doOp + ":" + order);
                record(order, "done:" + s.doOp);
            }
            if (failedAt < 0) { record(order, "COMPLETED"); return; }
        }
        // Undo in reverse, including the failed step: it may have applied before timing out. Undo is a no-op if not.
        for (int i = failedAt; i >= 0; i--) {
            Service s = STEPS.get(i);
            if (log.contains("undone:" + s.undoOp)) continue;
            withRetry(() -> s.undo(order), 50);   // compensations must eventually succeed; a real system alerts a human
            faults.crashPoint(s.undoOp + ":" + order);
            record(order, "undone:" + s.undoOp);
        }
        record(order, "COMPENSATED");
    }

    /** Keeps restarting a fresh orchestrator after each crash, like k8s restarting a pod. */
    static void runUntilDone(String order) {
        while (true) {
            try { run(order); return; }
            catch (Crash c) { crashes++; say("    💥 " + c.getMessage() + ", before writing the log. Restarting orchestrator…"); }
        }
    }

    static void scenario(String title, String order) {
        say("\n" + title);
        runUntilDone(order);
        say("  → " + order + ": " + LOG.get(order).get(LOG.get(order).size() - 1));
    }

    public static void main(String[] args) {
        System.out.println("=== Order saga: reserve → charge → ship ===");
        scenario("1) Happy path", "o1");

        faults.timeoutAfter.put("charge:o2", 2);
        scenario("2) Payment charges the card, but the response times out twice", "o2");

        faults.permanent.add("ship:o3");
        scenario("3) Shipping rejects permanently (pincode not serviceable) → compensate in reverse", "o3");

        faults.crashAfter.add("charge:o4");
        scenario("4) Orchestrator crashes after the charge succeeds, before logging it", "o4");

        faults.permanent.add("ship:o5");
        faults.timeoutBefore.put("refund:o5", 2);
        scenario("5) Shipping rejects, and the refund itself times out twice", "o5");

        System.out.printf("%nscripted totals: charges=%d refunds=%d (o3 and o5 refunded), duplicate calls absorbed=%d%n",
                PAYMENT.doEffects, PAYMENT.undoEffects, duplicates);

        // 6) Chaos: 200 orders, random timeouts (half after the work happened), crashes, permanent rejections.
        quiet = true; retries = 0; duplicates = 0; crashes = 0;
        for (Service s : STEPS) { s.doEffects = 0; s.undoEffects = 0; }
        faults = new Faults();
        faults.rnd = new Random(42); faults.pTimeout = 0.20; faults.pCrash = 0.05;
        Random pick = new Random(7);
        for (int n = 1; n <= 200; n++) {
            String order = "c" + n;
            for (Service s : STEPS) if (pick.nextDouble() < 0.05) faults.permanent.add(s.doOp + ":" + order);
            runUntilDone(order);
        }
        int completed = 0, compensated = 0, violations = 0;
        for (int n = 1; n <= 200; n++) {
            String order = "c" + n;
            boolean done = LOG.get(order).contains("COMPLETED");
            if (done) completed++; else compensated++;
            for (Service s : STEPS) if (Boolean.TRUE.equals(s.applied.get(order)) != done) violations++;
        }
        System.out.println("\n6) Chaos run: 200 orders, 20% timeouts, 5% crashes after any step, 5% permanent rejections per step");
        System.out.printf("   completed=%d compensated=%d crashes survived=%d retries=%d duplicate calls absorbed=%d%n",
                completed, compensated, crashes, retries, duplicates);
        System.out.printf("   cards charged=%d, refunded=%d → net charged=%d for %d completed orders%n",
                PAYMENT.doEffects, PAYMENT.undoEffects, PAYMENT.doEffects - PAYMENT.undoEffects, completed);
        System.out.printf("   steps left in the wrong state (half-done orders): %d%n", violations);
    }
}
