package wallet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The wallet. Every way money moves (top-up, transfer, refund, hold, capture, release) goes through
 * {@link #post}, which: locks all accounts involved in id order, checks funds and limits, writes balanced
 * ledger entries, and updates the cached balances, all as one step. Every public call takes an
 * idempotency key: the same key returns the same Txn, even when the duplicates arrive at the same time.
 */
final class TransferService {
    static final String BANK = "SYS:BANK";     // the outside world: goes negative as money is added
    static final String HOLDS = "SYS:HOLDS";   // money reserved for merchants, not yet captured
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One movement: amount leaves `from` and arrives in `to`. A Txn is one or more legs. */
    record Leg(String from, String to, long amountPaise) {}

    private record Slot(String fingerprint, CompletableFuture<Txn> result) {}

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Txn> txns = new ConcurrentHashMap<>();
    private final Map<String, Slot> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Long> refundedPaise = new ConcurrentHashMap<>();   // original txn id -> refunded so far
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Ledger ledger = new Ledger();
    private final AtomicLong txnSeq = new AtomicLong();
    private final AtomicLong entrySeq = new AtomicLong();
    private final Clock clock;
    private final Duration holdTtl;

    TransferService(Clock clock, Duration holdTtl) {
        this.clock = clock;
        this.holdTtl = holdTtl;
        open(BANK, Account.Type.SYSTEM, KycTier.NONE);
        open(HOLDS, Account.Type.SYSTEM, KycTier.NONE);
    }

    Account open(String id, Account.Type type, KycTier tier) {
        Account a = new Account(id, type, tier);
        if (accounts.putIfAbsent(id, a) != null) throw new IllegalArgumentException("account exists: " + id);
        return a;
    }

    // ======================================================================= public operations

    Txn addMoney(String key, String to, long amount) {
        return idempotent(key, "TOP_UP|" + to + "|" + amount, () ->
                post(key, Txn.Type.TOP_UP, BANK, to, amount, null, List.of(new Leg(BANK, to, amount)), () -> null, t -> {}));
    }

    Txn transfer(String key, String from, String to, long amount) {
        requireDifferent(from, to);
        return idempotent(key, "TRANSFER|" + from + "|" + to + "|" + amount, () ->
                post(key, Txn.Type.TRANSFER, from, to, amount, null, List.of(new Leg(from, to, amount)), () -> null, t -> {}));
    }

    /** Partial or full refund of a transfer or capture: NEW entries in the opposite direction. Nothing is deleted. */
    Txn refund(String key, String originalTxnId, long amount) {
        return idempotent(key, "REFUND|" + originalTxnId + "|" + amount, () -> {
            Txn orig = txns.get(originalTxnId);
            if (orig == null || !orig.ok() || (orig.type() != Txn.Type.TRANSFER && orig.type() != Txn.Type.CAPTURE))
                return reject(key, Txn.Type.REFUND, null, null, amount, originalTxnId, "nothing refundable with id " + originalTxnId);
            // runs under the locks of both accounts, so two refunds of the same txn can't both pass
            Supplier<String> check = () -> {
                long left = orig.amountPaise() - refundedPaise.getOrDefault(orig.id(), 0L);
                return amount > left ? "refund " + Money.format(amount) + " exceeds refundable " + Money.format(left) : null;
            };
            return post(key, Txn.Type.REFUND, orig.to(), orig.from(), amount, orig.id(),
                    List.of(new Leg(orig.to(), orig.from(), amount)), check,
                    t -> refundedPaise.merge(orig.id(), amount, Long::sum));
        });
    }

    /** Reserve money for a merchant (step 1 of a card-style payment). It leaves the customer's balance now. */
    Txn authorize(String key, String customer, String merchant, long amount) {
        requireDifferent(customer, merchant);
        account(merchant);
        return idempotent(key, "HOLD|" + customer + "|" + merchant + "|" + amount, () ->
                post(key, Txn.Type.HOLD, customer, merchant, amount, null, List.of(new Leg(customer, HOLDS, amount)),
                        () -> null,
                        t -> holds.put(t.id(), new Hold(t.id(), customer, merchant, amount, t.at().plus(holdTtl)))));
    }

    /** Pay the merchant up to the held amount; any remainder goes back to the customer in the same txn. */
    Txn capture(String key, String holdTxnId, long amount) {
        Hold h = hold(holdTxnId);
        return idempotent(key, "CAPTURE|" + holdTxnId + "|" + amount, () -> {
            List<Leg> legs = new ArrayList<>();
            legs.add(new Leg(HOLDS, h.merchant, amount));
            if (amount < h.amountPaise) legs.add(new Leg(HOLDS, h.customer, h.amountPaise - amount));
            Supplier<String> check = () -> {
                if (h.state != Hold.State.AUTHORIZED) return "hold is " + h.state;
                if (!clock.instant().isBefore(h.expiresAt)) return "hold expired";
                return amount > h.amountPaise ? "capture exceeds hold of " + Money.format(h.amountPaise) : null;
            };
            return post(key, Txn.Type.CAPTURE, h.customer, h.merchant, amount, holdTxnId, legs, check,
                    t -> h.state = Hold.State.CAPTURED);
        });
    }

    /** Cancel the reservation: the full amount goes back to the customer. */
    Txn voidHold(String key, String holdTxnId) { return release(key, holdTxnId, Hold.State.VOIDED); }

    /** Housekeeping job: release every hold whose time is up. Returns how many were released. */
    int expireHolds() {
        int n = 0;
        for (Hold h : holds.values())
            if (h.state == Hold.State.AUTHORIZED && !clock.instant().isBefore(h.expiresAt)
                    && release("expire:" + h.holdTxnId, h.holdTxnId, Hold.State.EXPIRED).ok()) n++;
        return n;
    }

    private Txn release(String key, String holdTxnId, Hold.State endState) {
        Hold h = hold(holdTxnId);
        return idempotent(key, "RELEASE|" + holdTxnId, () ->
                post(key, Txn.Type.RELEASE, h.merchant, h.customer, h.amountPaise, holdTxnId,
                        List.of(new Leg(HOLDS, h.customer, h.amountPaise)),
                        () -> h.state == Hold.State.AUTHORIZED ? null : "hold is " + h.state,
                        t -> h.state = endState));
    }

    // ======================================================================= idempotency

    /**
     * First caller with a key puts a not-yet-finished future in the map (putIfAbsent is atomic) and does the work.
     * Anyone else with the same key waits on that future and gets the identical Txn. Same key with a different
     * request is a client bug and is refused (like Stripe's 422 for a reused key).
     */
    private Txn idempotent(String key, String fingerprint, Supplier<Txn> work) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key required");
        Slot mine = new Slot(fingerprint, new CompletableFuture<>());
        Slot existing = idempotency.putIfAbsent(key, mine);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint))
                throw new IllegalArgumentException("idempotency key '" + key + "' was used for a different request");
            return existing.result().join();
        }
        try {
            Txn t = work.get();
            mine.result().complete(t);
            return t;
        } catch (RuntimeException e) {           // a crash, not a business "no": forget the key so a retry can run
            idempotency.remove(key, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }

    // ======================================================================= the one place money moves

    private Txn post(String key, Txn.Type type, String from, String to, long amount, String related,
                     List<Leg> legs, Supplier<String> check, Consumer<Txn> onCommit) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive, got " + amount);
        List<Account> locked = lockOrder(legs);
        for (Account a : locked) a.lock.lock();             // always in id order: no two threads wait on each other
        try {
            Instant now = clock.instant();
            String reason = check.get();
            if (reason == null) reason = fundsAndLimits(type, legs, now);
            if (reason != null) return reject(key, type, from, to, amount, related, reason);

            Txn t = new Txn("T" + txnSeq.incrementAndGet(), key, type, from, to, amount, Txn.Status.COMPLETED, null, related, now);
            List<LedgerEntry> batch = new ArrayList<>();
            LocalDate today = LocalDate.ofInstant(now, IST);
            for (Leg l : legs) {
                Account src = account(l.from()), dst = account(l.to());
                src.apply(-l.amountPaise());
                dst.apply(l.amountPaise());
                if (countsAsSending(type) && src.type != Account.Type.SYSTEM) src.addSent(today, l.amountPaise());
                batch.add(new LedgerEntry("E" + entrySeq.incrementAndGet(), t.id(), src.id, -l.amountPaise(), src.balance(), now));
                batch.add(new LedgerEntry("E" + entrySeq.incrementAndGet(), t.id(), dst.id, l.amountPaise(), dst.balance(), now));
            }
            ledger.append(batch);     // in a database: the INSERTs and UPDATEs share one transaction
            txns.put(t.id(), t);
            onCommit.accept(t);
            return t;
        } finally {
            for (int i = locked.size() - 1; i >= 0; i--) locked.get(i).lock.unlock();
        }
    }

    /** Distinct accounts of all legs, sorted by id. Remove the sort and opposite transfers can deadlock. */
    private List<Account> lockOrder(List<Leg> legs) {
        Set<String> ids = new LinkedHashSet<>();
        for (Leg l : legs) { ids.add(l.from()); ids.add(l.to()); }
        List<Account> list = new ArrayList<>(ids.stream().map(this::account).toList());
        list.sort(Comparator.comparing(a -> a.id));
        return list;
    }

    /** Called with every involved account locked, so balances and daily totals can't change under us. */
    private String fundsAndLimits(Txn.Type type, List<Leg> legs, Instant now) {
        Map<String, Long> net = new HashMap<>();
        for (Leg l : legs) {
            net.merge(l.from(), -l.amountPaise(), Long::sum);
            net.merge(l.to(), l.amountPaise(), Long::sum);
        }
        LocalDate today = LocalDate.ofInstant(now, IST);
        for (var e : net.entrySet()) {
            Account a = account(e.getKey());
            long after = a.balance() + e.getValue();
            if (after < 0 && !a.mayGoNegative()) return "insufficient funds in " + a.id;
            if (e.getValue() < 0 && countsAsSending(type)) {
                long out = -e.getValue();
                if (out > a.tier.perTxnPaise) return "above per-transaction limit of " + Money.format(a.tier.perTxnPaise);
                if (a.sentOn(today) + out > a.tier.dailyOutPaise) return "above daily limit of " + Money.format(a.tier.dailyOutPaise);
            }
            if (e.getValue() > 0 && (type == Txn.Type.TOP_UP || type == Txn.Type.TRANSFER) && after > a.tier.maxBalancePaise)
                return a.id + " would exceed wallet balance cap of " + Money.format(a.tier.maxBalancePaise);
        }
        return null;
    }

    private static boolean countsAsSending(Txn.Type type) { return type == Txn.Type.TRANSFER || type == Txn.Type.HOLD; }

    private Txn reject(String key, Txn.Type type, String from, String to, long amount, String related, String reason) {
        Txn t = new Txn("R" + txnSeq.incrementAndGet(), key, type, from, to, amount, Txn.Status.REJECTED, reason, related, clock.instant());
        txns.put(t.id(), t);
        return t;
    }

    // ======================================================================= reads

    long balance(String accountId) { return account(accountId).balance(); }
    long balanceFromLedger(String accountId) { return ledger.sumFor(accountId); }
    Ledger ledger() { return ledger; }
    Txn txn(String id) { return txns.get(id); }
    Hold hold(String holdTxnId) {
        Hold h = holds.get(holdTxnId);
        if (h == null) throw new IllegalArgumentException("no hold " + holdTxnId);
        return h;
    }
    List<Account> accounts() { return List.copyOf(accounts.values()); }

    /** Mini statement: every entry for the account, oldest first, with the running balance stored on it. */
    List<String> statement(String accountId) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MMM HH:mm").withZone(IST);
        List<String> lines = new ArrayList<>();
        for (LedgerEntry e : ledger.forAccount(accountId)) {
            Txn t = txns.get(e.txnId());
            String party = e.amountPaise() < 0 ? t.to() : t.from();
            String other = party.equals(accountId) ? "unused hold back"            // remainder of a partial capture
                    : (e.amountPaise() < 0 ? "to " : "from ") + party;
            lines.add(String.format("%s  %-4s %-9s %-20s %11s  bal %10s", f.format(e.at()), t.id(), t.type(), other,
                    (e.amountPaise() > 0 ? "+" : "") + Money.format(e.amountPaise()), Money.format(e.balanceAfterPaise())));
        }
        return lines;
    }

    private Account account(String id) {
        Account a = accounts.get(id);
        if (a == null) throw new IllegalArgumentException("no account " + id);
        return a;
    }

    private static void requireDifferent(String a, String b) {
        if (a.equals(b)) throw new IllegalArgumentException("from and to are the same account");
    }
}
