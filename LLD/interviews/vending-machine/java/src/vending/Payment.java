package vending;

import java.util.Map;

/**
 * How a sale was paid. Sealed: the compiler knows every kind, so a switch over Payment that forgets one
 * (say a future Card record) does not compile. Refunds differ per kind: cash = give back the very coins,
 * UPI = ask the bank to reverse the payment, identified by its idempotency key.
 */
public sealed interface Payment permits Payment.Cash, Payment.Upi {
    long amountPaise();

    /** The exact coins and notes the customer inserted (so a refund returns those, not "equivalent" ones). */
    record Cash(Map<Denomination, Integer> inserted) implements Payment {
        public Cash { inserted = Map.copyOf(inserted); }

        @Override public long amountPaise() { return CoinBox.total(inserted); }
    }

    /** A UPI payment for one order. The key is unique per order and makes duplicate bank callbacks harmless. */
    record Upi(String idempotencyKey, long amountPaise) implements Payment {}
}
