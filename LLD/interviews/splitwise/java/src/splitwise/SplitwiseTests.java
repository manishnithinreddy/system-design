package splitwise;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class SplitwiseTests {
    private static int passed = 0;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        equalSplitHandsOutLeftoverPaise();
        percentAndSharesAlwaysSumToTotal();
        invalidSplitsAreRejected();
        balancesFromATrip();
        settleUpAndDeleteAreNewEntries();
        addExpenseIsIdempotent();
        simplifyDebtsSettlesEveryone();
        randomExpensesKeepInvariants();
        concurrentExpensesAreNotLost();
        System.out.println("All " + passed + " tests passed.");
    }

    static void equalSplitHandsOutLeftoverPaise() {
        SortedMap<String, Long> s = Splitter.split(Money.parse("100.00"), new SplitSpec.Equal(List.of("c", "a", "b")));
        assertEquals(Map.of("a", 3334L, "b", 3333L, "c", 3333L), s, "₹100 / 3: 1 leftover paisa to 'a' (tie -> by id)");
        pass("equalSplitHandsOutLeftoverPaise");
    }

    static void percentAndSharesAlwaysSumToTotal() {
        var pct = Splitter.split(1001, new SplitSpec.Percent(Map.of(
                "a", new BigDecimal("33.33"), "b", new BigDecimal("33.33"), "c", new BigDecimal("33.34"))));
        assertEquals(1001L, sum(pct), "percent parts sum to total");
        // 1001 * 33.34% = 333.7334 -> floor 333, biggest fraction -> gets the extra paisa
        assertEquals(334L, pct.get("c"), "largest fractional part gets the leftover");

        var shares = Splitter.split(Money.parse("1000"), new SplitSpec.Shares(Map.of("adult1", 2, "adult2", 2, "kid", 1)));
        assertEquals(Map.of("adult1", 40000L, "adult2", 40000L, "kid", 20000L), shares, "2:2:1 shares");
        pass("percentAndSharesAlwaysSumToTotal");
    }

    static void invalidSplitsAreRejected() {
        assertThrows(() -> Splitter.split(1000, new SplitSpec.Exact(Map.of("a", 600L, "b", 300L))), "exact must sum");
        assertThrows(() -> Splitter.split(1000, new SplitSpec.Percent(Map.of("a", new BigDecimal("50")))), "percent must be 100");
        assertThrows(() -> Splitter.split(1000, new SplitSpec.Shares(Map.of("a", 0))), "shares positive");
        assertThrows(() -> Splitter.split(0, new SplitSpec.Equal(List.of("a"))), "positive total");
        assertThrows(() -> Money.parse("10.001"), "no fractional paise");
        pass("invalidSplitsAreRejected");
    }

    static ExpenseService goaTrip() {
        ExpenseService svc = new ExpenseService(CLOCK);
        svc.createGroup("goa", Set.of("asha", "bala", "chitra"));
        // Asha pays hotel ₹9,000 split equally
        svc.addExpense("goa", "r1", "asha", Money.parse("9000"), new SplitSpec.Equal(List.of("asha", "bala", "chitra")), "Hotel");
        // Bala pays dinner ₹1,500: Bala 500, Chitra 1000 (Asha skipped dinner)
        svc.addExpense("goa", "r2", "bala", Money.parse("1500"),
                new SplitSpec.Exact(Map.of("bala", Money.parse("500"), "chitra", Money.parse("1000"))), "Dinner");
        return svc;
    }

    static void balancesFromATrip() {
        ExpenseService svc = goaTrip();
        // asha: paid 9000, owes 3000 -> +6000 ; bala: paid 1500, owes 3000+500 -> -2000 ; chitra: owes 3000+1000 -> -4000
        assertEquals(Map.of("asha", 600000L, "bala", -200000L, "chitra", -400000L), svc.balances("goa"), "net balances");
        assertEquals(0L, sum(svc.balances("goa")), "balances sum to zero");
        pass("balancesFromATrip");
    }

    static void settleUpAndDeleteAreNewEntries() {
        ExpenseService svc = goaTrip();
        svc.settleUp("goa", "chitra", "asha", Money.parse("4000"));
        assertEquals(0L, svc.balances("goa").get("chitra"), "chitra settled");
        String dinnerId = svc.history("goa").get(1).id();
        svc.deleteExpense("goa", dinnerId);
        // dinner reversed: bala +1000 (no longer paid 1500 minus owed 500), chitra +1000
        assertEquals(Map.of("asha", 200000L, "bala", -300000L, "chitra", 100000L), svc.balances("goa"), "after delete");
        assertEquals(4, svc.history("goa").size(), "history keeps everything: 2 expenses, 1 settlement, 1 reversal");
        assertThrows(() -> svc.deleteExpense("goa", dinnerId), "can't delete twice");
        pass("settleUpAndDeleteAreNewEntries");
    }

    static void addExpenseIsIdempotent() {
        ExpenseService svc = goaTrip();
        var again = svc.addExpense("goa", "r1", "asha", Money.parse("9000"),
                new SplitSpec.Equal(List.of("asha", "bala", "chitra")), "Hotel (retry)");
        assertEquals(svc.history("goa").get(0), again, "retry returns the original expense");
        assertEquals(2, svc.history("goa").size(), "no duplicate");
        assertThrows(() -> svc.addExpense("goa", "r1", "asha", Money.parse("10"),
                new SplitSpec.Equal(List.of("asha", "bala")), "different"), "same id, different request");
        pass("addExpenseIsIdempotent");
    }

    static void simplifyDebtsSettlesEveryone() {
        ExpenseService svc = goaTrip();
        List<Transfer> t = svc.simplifiedDebts("goa");
        assertEquals(List.of(new Transfer("chitra", "asha", 400000L), new Transfer("bala", "asha", 200000L)), t,
                "two payments settle a 3-person group");
        pass("simplifyDebtsSettlesEveryone");
    }

    /** Property test: random groups and expenses; balances always sum to 0 and simplification settles everyone. */
    static void randomExpensesKeepInvariants() {
        Random rnd = new Random(7);
        for (int round = 0; round < 300; round++) {
            int n = 2 + rnd.nextInt(8);
            List<String> people = new ArrayList<>();
            for (int i = 0; i < n; i++) people.add("u" + i);
            ExpenseService svc = new ExpenseService(CLOCK);
            svc.createGroup("g", Set.copyOf(people));
            for (int e = 0; e < 20; e++) {
                String payer = people.get(rnd.nextInt(n));
                long total = 1 + rnd.nextInt(1_000_000);
                Map<String, Integer> shares = new HashMap<>();
                for (String p : people) if (rnd.nextBoolean()) shares.put(p, 1 + rnd.nextInt(5));
                if (shares.isEmpty()) shares.put(payer, 1);
                svc.addExpense("g", "r" + e, payer, total, new SplitSpec.Shares(shares), "random");
            }
            SortedMap<String, Long> bal = svc.balances("g");
            assertEquals(0L, sum(bal), "sum to zero (round " + round + ")");
            List<Transfer> transfers = DebtSimplifier.simplify(bal);
            if (transfers.size() > n - 1) throw new AssertionError("more than n-1 transfers");
            Map<String, Long> after = new HashMap<>(bal);
            for (Transfer t : transfers) {
                after.merge(t.from(), t.amountPaise(), Long::sum);
                after.merge(t.to(), -t.amountPaise(), Long::sum);
            }
            if (after.values().stream().anyMatch(v -> v != 0)) throw new AssertionError("not settled: " + after);
        }
        pass("randomExpensesKeepInvariants");
    }

    static void concurrentExpensesAreNotLost() throws Exception {
        ExpenseService svc = new ExpenseService(CLOCK);
        svc.createGroup("flat", Set.of("a", "b"));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                int thread = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        svc.addExpense("flat", thread + "-" + i, "a", 200, new SplitSpec.Equal(List.of("a", "b")), "chai");
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertEquals(8000, svc.history("flat").size(), "16 x 500 expenses recorded");
        assertEquals(-800000L, svc.balances("flat").get("b"), "b owes 8000 x ₹1");
        pass("concurrentExpensesAreNotLost");
    }

    // --- helpers ---

    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void assertThrows(Runnable r, String what) {
        try {
            r.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(what + ": expected an exception");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
