package wallet;

import java.time.LocalDate;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One ledger account: a user's wallet, a merchant's wallet, or a SYSTEM account (money that came in from
 * banks, money on hold). The balance is a CACHE of the sum of its ledger entries, changed only while
 * holding {@link #lock}, in the same step that writes the entries; version counts those changes.
 */
final class Account {
    enum Type { USER, MERCHANT, SYSTEM }

    final String id;
    final Type type;
    final KycTier tier;
    final ReentrantLock lock = new ReentrantLock();

    private volatile long balancePaise;     // volatile: readers outside the lock see the latest committed value
    private volatile long version;
    private LocalDate sentDay;
    private long sentTodayPaise;

    Account(String id, Type type, KycTier tier) { this.id = id; this.type = type; this.tier = tier; }

    long balance() { return balancePaise; }
    long version() { return version; }

    /** SYSTEM accounts may go negative: "BANK" at -₹5,000 means ₹5,000 came in from outside. */
    boolean mayGoNegative() { return type == Type.SYSTEM; }

    // ---- everything below: caller must hold lock ----
    void apply(long deltaPaise) {
        assert lock.isHeldByCurrentThread();
        balancePaise = Math.addExact(balancePaise, deltaPaise);
        version++;
    }

    long sentOn(LocalDate day) { return day.equals(sentDay) ? sentTodayPaise : 0; }

    void addSent(LocalDate day, long amount) {
        if (!day.equals(sentDay)) { sentDay = day; sentTodayPaise = 0; }
        sentTodayPaise += amount;
    }
}
