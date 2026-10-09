package wallet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Plain-Java tests (no JUnit) so the code runs with just javac + java. */
public final class WalletTests {
    private static int passed = 0;
    static final Instant T0 = Instant.parse("2026-10-09T04:30:00Z");   // 10:00 in India
    static final String CARD = "4111111111111234";
    static final long R500 = Money.rupees(500), R200 = Money.rupees(200), R100 = Money.rupees(100);

    public static void main(String[] args) throws Exception {
        // ATM
        atmHappyPathDispensesAndPrintsReceipt();
        pinLockoutAfterThreeWrongTries();
        insufficientFundsIsDeclinedAndNothingDispensed();
        greedyPlanForNormalCassettes();
        greedyFailsWithLimitedCassettesExactSearchFindsPlan();
        dispenseFailureReversesTheDebit();
        reversalThatTimesOutIsQueuedAndRetried();
        bankTimeoutNeverDoubleDebits();
        dailyWithdrawalLimitResetsNextDay();
        // wallet
        transferMovesMoneyAndWritesTwoEntries();
        balanceEqualsSumOfEntries();
        insufficientFundsRejectedWithNoEntries();
        idempotentRetryReturnsSameTxn();
        concurrentDuplicatesWithSameKeyCreateOneTransfer();
        oppositeTransfersDoNotDeadlock();
        moneyIsConservedUnderConcurrentRandomTransfers();
        refundWritesReversingEntries();
        holdThenCapture();
        holdThenVoidAndHoldExpiry();
        kycLimitsAreEnforced();
        moneyAsLongPaiseIsExact();
        System.out.println("All " + passed + " tests passed.");
        System.exit(0);   // belt and braces: never hang on a stuck (daemon) test thread
    }

    // ================================================================== ATM helpers

    static MutableClock clock() { return new MutableClock(T0, TransferService.IST); }

    static Map<Long, Integer> cassettes(int n500, int n200, int n100) { return Map.of(R500, n500, R200, n200, R100, n100); }

    static InMemoryBank bank(MutableClock clock, long balanceRupees) {
        InMemoryBank b = new InMemoryBank(clock, Money.rupees(25_000));
        b.openCard(CARD, "1234", Money.rupees(balanceRupees));
        return b;
    }

    private static int atmCount = 0;

    /** A fresh ATM id each time: refs are "ATM id + sequence", and a real ATM persists its sequence. */
    static Atm loggedIn(InMemoryBank bank, CashDispenser d, MutableClock clock) {
        Atm atm = new Atm("ATM-" + (++atmCount), d, bank, clock);
        atm.insertCard(CARD);
        assertEquals("PIN OK. Choose: balance or withdrawal", atm.enterPin("1234"), "login");
        return atm;
    }

    static Atm.Outcome lastOutcome(Atm atm) { return atm.journal().get(atm.journal().size() - 1).outcome(); }

    // ================================================================== ATM tests

    static void atmHappyPathDispensesAndPrintsReceipt() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 20_000);
        CashDispenser d = new CashDispenser(cassettes(10, 10, 10));
        Atm atm = new Atm("ATM-PUNE-07", d, bank, clock);
        assertTrue(atm.withdraw(R500).startsWith("Not available"), "no withdrawal before a card");
        assertEquals("Enter PIN", atm.insertCard(CARD), "card in");
        assertTrue(atm.withdraw(R500).startsWith("Not available"), "no withdrawal before the PIN");
        atm.enterPin("1234");
        assertEquals("Available balance: ₹20,000.00", atm.balance(), "balance enquiry");
        AtomicReference<AtmState> seenDuringMotor = new AtomicReference<>();
        d.setHardware(notes -> { seenDuringMotor.set(atm.state()); return true; });
        String out = atm.withdraw(Money.rupees(5_200));
        assertTrue(out.contains("10 x ₹500.00 + 1 x ₹200.00"), "notes: " + out);
        assertTrue(out.contains("XXXX1234") && out.contains("BAL   ₹14,800.00"), "receipt: " + out);
        assertTrue(seenDuringMotor.get() instanceof AtmState.Dispensing, "state is Dispensing while notes move");
        assertEquals(Money.rupees(14_800), bank.balance(CARD), "debited once");
        assertEquals(Money.rupees(1_800 + 1_000), d.totalPaise(), "cash left: 9 x 200 + 10 x 100");
        assertTrue(atm.state() instanceof AtmState.Idle, "session ends after a withdrawal");
        assertEquals(Atm.Outcome.DISPENSED, lastOutcome(atm), "journal");
        pass("atmHappyPathDispensesAndPrintsReceipt");
    }

    static void pinLockoutAfterThreeWrongTries() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 5_000);
        Atm atm = new Atm("ATM-1", new CashDispenser(cassettes(10, 10, 10)), bank, clock);
        atm.insertCard(CARD);
        assertEquals("Wrong PIN. Try again", atm.enterPin("0000"), "1st wrong");
        assertEquals("Wrong PIN. Try again", atm.enterPin("1111"), "2nd wrong");
        assertTrue(atm.enterPin("1234").startsWith("PIN OK"), "a correct PIN resets the counter");
        atm.cancel();
        atm.insertCard(CARD);
        atm.enterPin("0000");
        atm.enterPin("0000");
        assertTrue(atm.enterPin("0000").startsWith("Card blocked"), "3rd wrong in a row blocks");
        assertEquals(List.of(CARD), atm.retainedCards(), "card retained");
        assertTrue(atm.state() instanceof AtmState.Idle, "back to idle");
        atm.insertCard(CARD);
        assertTrue(atm.enterPin("1234").startsWith("Card blocked"), "blocked even with the right PIN later");
        pass("pinLockoutAfterThreeWrongTries");
    }

    static void insufficientFundsIsDeclinedAndNothingDispensed() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 1_000);
        CashDispenser d = new CashDispenser(cassettes(10, 10, 10));
        Atm atm = loggedIn(bank, d, clock);
        long cashBefore = d.totalPaise();
        assertEquals("Declined: INSUFFICIENT_FUNDS. Card returned", atm.withdraw(Money.rupees(2_000)), "declined");
        assertEquals(cashBefore, d.totalPaise(), "no notes moved");
        assertEquals(Money.rupees(1_000), bank.balance(CARD), "balance untouched");
        assertEquals(Atm.Outcome.DECLINED, lastOutcome(atm), "journal");
        pass("insufficientFundsIsDeclinedAndNothingDispensed");
    }

    static void greedyPlanForNormalCassettes() {
        assertEquals(Optional.of(Map.of(R500, 5, R200, 1, R100, 1)), NotePlanner.plan(Money.rupees(2_800), cassettes(10, 10, 10)), "2,800");
        assertEquals(Optional.of(Map.of(R200, 2)), NotePlanner.plan(Money.rupees(400), cassettes(10, 10, 10)), "400 = 2 x 200");
        assertEquals(Optional.of(Map.of(R500, 3, R100, 2)), NotePlanner.plan(Money.rupees(1_700), cassettes(3, 0, 5)), "no 200s");
        pass("greedyPlanForNormalCassettes");
    }

    static void greedyFailsWithLimitedCassettesExactSearchFindsPlan() {
        Map<Long, Integer> box = cassettes(1, 3, 0);              // one ₹500, three ₹200, no ₹100
        assertEquals(Optional.empty(), NotePlanner.greedy(Money.rupees(600), box), "greedy takes 500, stuck on 100");
        assertEquals(Optional.of(Map.of(R200, 3)), NotePlanner.exact(Money.rupees(600), box), "exact: 3 x 200");
        assertEquals(Optional.of(Map.of(R500, 2)), NotePlanner.exact(Money.rupees(1_000), cassettes(2, 5, 10)), "fewest notes");
        assertEquals(Optional.empty(), NotePlanner.plan(Money.rupees(100), cassettes(5, 5, 0)), "₹100 with no ₹100 notes");
        assertEquals(Optional.empty(), NotePlanner.plan(Money.rupees(30_000), cassettes(100, 0, 0)), "60 notes > 40-note cap");
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 5_000);
        Atm atm = loggedIn(bank, new CashDispenser(box), clock);
        assertTrue(atm.withdraw(Money.rupees(600)).contains("3 x ₹200.00"), "ATM uses the exact plan");
        pass("greedyFailsWithLimitedCassettesExactSearchFindsPlan");
    }

    static void dispenseFailureReversesTheDebit() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 10_000);
        CashDispenser d = new CashDispenser(cassettes(10, 10, 10));
        d.setHardware(notes -> false);                            // jam
        Atm atm = loggedIn(bank, d, clock);
        assertTrue(atm.withdraw(Money.rupees(3_000)).startsWith("Unable to dispense"), "customer told");
        assertEquals(Money.rupees(10_000), bank.balance(CARD), "debit reversed");
        assertEquals(Atm.Outcome.DISPENSE_FAILED_REVERSED, lastOutcome(atm), "journal");
        assertTrue(atm.state() instanceof AtmState.OutOfService, "machine takes itself out of service");
        assertTrue(atm.insertCard(CARD).startsWith("Out of service"), "refuses the next customer");
        d.setHardware(notes -> true);
        atm.refill(R100, 0);                                      // technician visit
        assertEquals("Enter PIN", atm.insertCard(CARD), "back in service");
        pass("dispenseFailureReversesTheDebit");
    }

    static void reversalThatTimesOutIsQueuedAndRetried() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 10_000);
        CashDispenser d = new CashDispenser(cassettes(10, 10, 10));
        d.setHardware(notes -> false);
        bank.failNextReverse();
        Atm atm = loggedIn(bank, d, clock);
        atm.withdraw(Money.rupees(3_000));
        assertEquals(Atm.Outcome.REVERSAL_PENDING, lastOutcome(atm), "journal says pending");
        assertEquals(Money.rupees(7_000), bank.balance(CARD), "still debited for now");
        assertEquals(1, atm.pendingReversals().size(), "reversal queued, not forgotten");
        atm.refill(R100, 0);
        assertEquals(Money.rupees(10_000), bank.balance(CARD), "retry succeeded");
        assertEquals(0, atm.pendingReversals().size(), "queue drained");
        pass("reversalThatTimesOutIsQueuedAndRetried");
    }

    static void bankTimeoutNeverDoubleDebits() throws Exception {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 10_000);
        CashDispenser d = new CashDispenser(cassettes(10, 10, 10));
        long cash = d.totalPaise();
        Atm atm = loggedIn(bank, d, clock);
        bank.failNextDebit(InMemoryBank.Fault.TIMEOUT_AFTER_APPLY);     // bank debits, reply lost
        assertTrue(atm.withdraw(Money.rupees(2_000)).contains("no reply"), "customer told");
        assertEquals(Money.rupees(10_000), bank.balance(CARD), "applied debit was reversed");
        assertEquals(cash, d.totalPaise(), "no cash on an unknown outcome");
        assertEquals(Atm.Outcome.TIMEOUT_REVERSED, lastOutcome(atm), "journal");

        bank.failNextDebit(InMemoryBank.Fault.TIMEOUT_BEFORE_APPLY);    // request lost on the way
        atm = loggedIn(bank, d, clock);
        atm.withdraw(Money.rupees(2_000));
        String lostRef = atm.journal().get(0).ref();
        assertEquals(BankService.DebitResult.ALREADY_REVERSED, bank.debit(CARD, Money.rupees(2_000), lostRef),
                "a late copy of the lost request is refused");
        assertEquals(Money.rupees(10_000), bank.balance(CARD), "still no debit");

        assertEquals(BankService.DebitResult.APPROVED, bank.debit(CARD, R500, "retry-ref"), "first");
        assertEquals(BankService.DebitResult.APPROVED, bank.debit(CARD, R500, "retry-ref"), "network retry, same ref");
        assertEquals(Money.rupees(9_500), bank.balance(CARD), "debited once");
        pass("bankTimeoutNeverDoubleDebits");
    }

    static void dailyWithdrawalLimitResetsNextDay() {
        MutableClock clock = clock();
        InMemoryBank bank = bank(clock, 1_00_000);
        CashDispenser d = new CashDispenser(cassettes(200, 0, 0));
        for (int i = 0; i < 2; i++) assertTrue(loggedIn(bank, d, clock).withdraw(Money.rupees(10_000)).startsWith("Please take"), "10k #" + i);
        assertEquals("Declined: DAILY_LIMIT_EXCEEDED. Card returned", loggedIn(bank, d, clock).withdraw(Money.rupees(10_000)), "25k limit");
        assertTrue(loggedIn(bank, d, clock).withdraw(Money.rupees(5_000)).startsWith("Please take"), "exactly up to the limit");
        clock.advance(Duration.ofDays(1));
        assertTrue(loggedIn(bank, d, clock).withdraw(Money.rupees(10_000)).startsWith("Please take"), "new day, new limit");
        assertEquals(Money.rupees(65_000), bank.balance(CARD), "35k withdrawn in total");
        pass("dailyWithdrawalLimitResetsNextDay");
    }

    // ================================================================== wallet helpers

    static TransferService wallet(MutableClock clock) {
        TransferService w = new TransferService(clock, Duration.ofMinutes(15));
        w.open("alice", Account.Type.USER, KycTier.FULL_KYC);
        w.open("bob", Account.Type.USER, KycTier.FULL_KYC);
        w.open("swiggy", Account.Type.MERCHANT, KycTier.NONE);
        return w;
    }

    static void assertLedgerConsistent(TransferService w) {
        long total = 0;
        for (Account a : w.accounts()) {
            assertEquals(w.balanceFromLedger(a.id), a.balance(), "cached balance == sum of entries for " + a.id);
            if (a.type != Account.Type.SYSTEM) assertTrue(a.balance() >= 0, a.id + " never negative");
            total += a.balance();
        }
        assertEquals(0L, total, "all accounts incl. system add up to zero");
    }

    /** Starts n daemon threads that wait on one latch, so they really run at the same moment. */
    static List<Thread> startTogether(int n, IntConsumer body, Queue<Throwable> errors) {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                try { go.await(); body.accept(id); } catch (Throwable e) { errors.add(e); }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        go.countDown();
        return threads;
    }

    static void joinAll(List<Thread> threads, Duration timeout, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread t : threads) t.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
        for (Thread t : threads) assertTrue(!t.isAlive(), what + ": thread still running after " + timeout + " (deadlock?)");
    }

    // ================================================================== wallet tests

    static void transferMovesMoneyAndWritesTwoEntries() {
        TransferService w = wallet(clock());
        w.addMoney("top-1", "alice", Money.rupees(1_000));
        Txn t = w.transfer("pay-1", "alice", "bob", R200);
        assertTrue(t.ok(), "completed");
        assertEquals(Money.rupees(800), w.balance("alice"), "alice");
        assertEquals(R200, w.balance("bob"), "bob");
        List<LedgerEntry> entries = w.ledger().forTxn(t.id());
        assertEquals(2, entries.size(), "two entries");
        assertEquals(-R200, entries.get(0).amountPaise(), "debit alice");
        assertEquals(R200, entries.get(1).amountPaise(), "credit bob");
        assertEquals(2L, w.accounts().stream().filter(a -> a.id.equals("alice")).findFirst().get().version(), "version bumped per change");
        pass("transferMovesMoneyAndWritesTwoEntries");
    }

    static void balanceEqualsSumOfEntries() {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", Money.rupees(3_000));
        w.addMoney("t2", "bob", Money.rupees(500));
        Txn p = w.transfer("p1", "alice", "swiggy", Money.rupees(450));
        w.transfer("p2", "bob", "alice", Money.rupees(120));
        w.refund("r1", p.id(), Money.rupees(50));
        w.authorize("h1", "alice", "swiggy", R100);
        assertLedgerConsistent(w);
        List<LedgerEntry> mine = w.ledger().forAccount("alice");
        assertEquals(w.balance("alice"), mine.get(mine.size() - 1).balanceAfterPaise(), "last running balance");
        pass("balanceEqualsSumOfEntries");
    }

    static void insufficientFundsRejectedWithNoEntries() {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", R100);
        int before = w.ledger().size();
        Txn t = w.transfer("p1", "alice", "bob", R200);
        assertEquals(Txn.Status.REJECTED, t.status(), "rejected");
        assertTrue(t.reason().contains("insufficient"), "reason: " + t.reason());
        assertEquals(before, w.ledger().size(), "no entries written");
        assertEquals(R100, w.balance("alice"), "alice unchanged");
        assertEquals(0L, w.balance("bob"), "bob unchanged");
        pass("insufficientFundsRejectedWithNoEntries");
    }

    static void idempotentRetryReturnsSameTxn() {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", Money.rupees(1_000));
        Txn first = w.transfer("app-req-42", "alice", "bob", R200);
        Txn retry = w.transfer("app-req-42", "alice", "bob", R200);    // app hung, user tapped again
        assertTrue(first == retry, "the very same Txn comes back");
        assertEquals(Money.rupees(800), w.balance("alice"), "paid once");
        assertEquals(4, w.ledger().size(), "2 top-up + 2 transfer entries");
        assertThrows(IllegalArgumentException.class, () -> w.transfer("app-req-42", "alice", "bob", R500), "key reused for another amount");
        Txn no = w.transfer("big", "alice", "bob", Money.rupees(5_000));
        w.addMoney("t2", "alice", Money.rupees(10_000));
        assertTrue(no == w.transfer("big", "alice", "bob", Money.rupees(5_000)), "a stored 'no' replays as 'no'");
        assertTrue(w.transfer("big-2", "alice", "bob", Money.rupees(5_000)).ok(), "a new attempt needs a new key");
        pass("idempotentRetryReturnsSameTxn");
    }

    static void concurrentDuplicatesWithSameKeyCreateOneTransfer() throws Exception {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", Money.rupees(1_000));
        Set<String> ids = ConcurrentHashMap.newKeySet();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        joinAll(startTogether(16, i -> ids.add(w.transfer("dup-key", "alice", "bob", R200).id()), errors),
                Duration.ofSeconds(10), "duplicates");
        assertTrue(errors.isEmpty(), "no errors: " + errors);
        assertEquals(1, ids.size(), "16 callers, one txn id");
        assertEquals(R200, w.balance("bob"), "money moved once");
        assertEquals(2, w.ledger().forTxn(ids.iterator().next()).size(), "two entries in total");
        pass("concurrentDuplicatesWithSameKeyCreateOneTransfer");
    }

    static void oppositeTransfersDoNotDeadlock() throws Exception {
        TransferService w = new TransferService(clock(), Duration.ofMinutes(15));
        w.open("acct-A", Account.Type.USER, KycTier.NONE);
        w.open("acct-B", Account.Type.USER, KycTier.NONE);
        w.addMoney("a", "acct-A", Money.rupees(1_00_000));
        w.addMoney("b", "acct-B", Money.rupees(1_00_000));
        int perThread = 20_000;
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Thread> threads = startTogether(2, id -> {
            for (int i = 0; i < perThread; i++) {
                if (id == 0) w.transfer("ab-" + i, "acct-A", "acct-B", 100);
                else w.transfer("ba-" + i, "acct-B", "acct-A", 100);
            }
        }, errors);
        joinAll(threads, Duration.ofSeconds(20), "A->B vs B->A");
        assertTrue(errors.isEmpty(), "no errors: " + errors);
        assertEquals(Money.rupees(2_00_000), w.balance("acct-A") + w.balance("acct-B"), "nothing lost");
        assertLedgerConsistent(w);
        pass("oppositeTransfersDoNotDeadlock");
    }

    static void moneyIsConservedUnderConcurrentRandomTransfers() throws Exception {
        TransferService w = new TransferService(clock(), Duration.ofMinutes(15));
        int n = 8;
        for (int i = 0; i < n; i++) {
            w.open("u" + i, Account.Type.USER, KycTier.NONE);
            w.addMoney("seed-" + i, "u" + i, Money.rupees(10_000));
        }
        AtomicInteger done = new AtomicInteger(), completed = new AtomicInteger();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        joinAll(startTogether(8, id -> {
            Random r = new Random(id);                            // seeded: a failure can be replayed
            for (int i = 0; i < 1_250; i++) {
                int from = r.nextInt(n), to = (from + 1 + r.nextInt(n - 1)) % n;
                long amount = 1 + r.nextInt((int) Money.rupees(5_000));
                if (w.transfer("t" + id + "-" + i, "u" + from, "u" + to, amount).ok()) completed.incrementAndGet();
                done.incrementAndGet();
            }
        }, errors), Duration.ofSeconds(30), "random transfers");
        assertTrue(errors.isEmpty(), "no errors: " + errors);
        assertEquals(10_000, done.get(), "all transfers ran");
        long users = 0;
        for (int i = 0; i < n; i++) users += w.balance("u" + i);
        assertEquals(Money.rupees(80_000), users, "total user money unchanged");
        assertLedgerConsistent(w);
        assertEquals(2 * (n + completed.get()), w.ledger().size(), "2 entries per completed txn, none for rejected");
        pass("moneyIsConservedUnderConcurrentRandomTransfers (" + completed.get() + " completed, "
                + (10_000 - completed.get()) + " rejected)");
    }

    static void refundWritesReversingEntries() {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", Money.rupees(1_000));
        Txn pay = w.transfer("order-9", "alice", "swiggy", R500);
        Txn r1 = w.refund("refund-1", pay.id(), R200);                  // one item missing
        assertTrue(r1.ok(), "partial refund");
        List<LedgerEntry> e = w.ledger().forTxn(r1.id());
        assertEquals("swiggy", e.get(0).accountId(), "merchant debited");
        assertEquals("alice", e.get(1).accountId(), "customer credited");
        assertEquals(2, w.ledger().forTxn(pay.id()).size(), "original entries still there");
        assertEquals(Txn.Status.REJECTED, w.refund("refund-2", pay.id(), Money.rupees(400)).status(), "only ₹300 left");
        assertTrue(w.refund("refund-3", pay.id(), Money.rupees(300)).ok(), "rest refunded");
        assertEquals(Money.rupees(1_000), w.balance("alice"), "alice whole again");
        assertEquals(Txn.Status.REJECTED, w.refund("refund-4", "T999", R100).status(), "unknown txn");
        assertLedgerConsistent(w);
        pass("refundWritesReversingEntries");
    }

    static void holdThenCapture() {
        TransferService w = wallet(clock());
        w.addMoney("t1", "alice", Money.rupees(1_000));
        Txn h = w.authorize("ride-77-auth", "alice", "swiggy", Money.rupees(300));   // estimate
        assertEquals(Money.rupees(700), w.balance("alice"), "held money is not spendable");
        assertEquals(Money.rupees(300), w.balance(TransferService.HOLDS), "parked in HOLDS");
        Txn c = w.capture("ride-77-cap", h.id(), Money.rupees(250));                   // actual fare
        assertTrue(c.ok(), "captured");
        assertEquals(Money.rupees(250), w.balance("swiggy"), "merchant paid the actual amount");
        assertEquals(Money.rupees(750), w.balance("alice"), "₹50 unused hold came back");
        assertEquals(0L, w.balance(TransferService.HOLDS), "nothing left on hold");
        assertEquals(Hold.State.CAPTURED, w.hold(h.id()).state, "hold state");
        assertEquals(Txn.Status.REJECTED, w.capture("ride-77-cap-again", h.id(), Money.rupees(250)).status(), "no double capture");
        assertTrue(w.refund("ride-77-refund", c.id(), Money.rupees(250)).ok(), "a capture can be refunded");
        assertEquals(Money.rupees(1_000), w.balance("alice"), "full refund");
        assertLedgerConsistent(w);
        pass("holdThenCapture");
    }

    static void holdThenVoidAndHoldExpiry() {
        MutableClock clock = clock();
        TransferService w = wallet(clock);
        w.addMoney("t1", "alice", Money.rupees(1_000));
        Txn h1 = w.authorize("h1", "alice", "swiggy", Money.rupees(300));
        assertTrue(w.voidHold("v1", h1.id()).ok(), "void");
        assertEquals(Money.rupees(1_000), w.balance("alice"), "money back");
        assertEquals(Txn.Status.REJECTED, w.capture("c1", h1.id(), R100).status(), "no capture after void");
        Txn h2 = w.authorize("h2", "alice", "swiggy", Money.rupees(400));
        clock.advance(Duration.ofMinutes(16));
        assertTrue(w.capture("c2", h2.id(), Money.rupees(400)).reason().contains("expired"), "capture after TTL refused");
        assertEquals(1, w.expireHolds(), "sweeper releases it");
        assertEquals(Hold.State.EXPIRED, w.hold(h2.id()).state, "state");
        assertEquals(Money.rupees(1_000), w.balance("alice"), "money back after expiry");
        assertEquals(0, w.expireHolds(), "sweeper is idempotent");
        assertLedgerConsistent(w);
        pass("holdThenVoidAndHoldExpiry");
    }

    static void kycLimitsAreEnforced() {
        MutableClock clock = clock();
        TransferService w = wallet(clock);
        w.open("minnie", Account.Type.USER, KycTier.MIN_KYC);        // illustrative limits: 5k/txn, 10k/day, 10k cap
        assertTrue(w.addMoney("m1", "minnie", Money.rupees(10_000)).ok(), "up to the cap");
        assertTrue(w.addMoney("m2", "minnie", R100).reason().contains("cap"), "above balance cap");
        int entries = w.ledger().size();
        assertTrue(w.transfer("m3", "minnie", "bob", Money.rupees(6_000)).reason().contains("per-transaction"), "per txn");
        assertEquals(entries, w.ledger().size(), "rejected limit check writes nothing");
        assertTrue(w.transfer("m4", "minnie", "bob", Money.rupees(5_000)).ok(), "5k");
        w.addMoney("m5", "minnie", Money.rupees(5_000));
        assertTrue(w.transfer("m6", "minnie", "bob", Money.rupees(5_000)).ok(), "10k today");
        w.addMoney("m7", "minnie", Money.rupees(5_000));
        assertTrue(w.transfer("m8", "minnie", "bob", R100).reason().contains("daily"), "daily limit");
        clock.advance(Duration.ofDays(1));
        assertTrue(w.transfer("m9", "minnie", "bob", R100).ok(), "new day in IST");
        pass("kycLimitsAreEnforced");
    }

    static void moneyAsLongPaiseIsExact() {
        double d = 0;
        for (int i = 0; i < 10; i++) d += 0.10;                       // ten ₹0.10 payments as double
        assertTrue(d != 1.0, "double drifts: " + d);
        long p = 0;
        for (int i = 0; i < 10; i++) p += 10;                          // the same in paise
        assertEquals(100L, p, "long is exact");
        assertEquals("₹12,34,56,789.01", Money.format(12_345_678_901L), "Indian grouping");
        assertEquals("-₹0.50", Money.format(-50), "negative");
        assertThrows(ArithmeticException.class, () -> Money.rupees(Long.MAX_VALUE / 10), "overflow is loud");
        pass("moneyAsLongPaiseIsExact");
    }

    // ================================================================== tiny assert helpers

    interface ThrowingRunnable { void run() throws Exception; }

    static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable r, String what) {
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static void assertEquals(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    static void assertTrue(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }
}
