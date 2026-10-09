# Solutions (spoilers)

Starting state: `./run.sh` prints **10 passed, 3 failed**. There are **4** planted bugs; one has no failing test.

## Bug 1: off-by-one in the discount threshold (caught by a test)

- **Where:** `PriceCalculator.discount`, `subtotalPaise > DISCOUNT_THRESHOLD_PAISE`.
- **Why:** the rule says "at least Rs 1000", so exactly 100_000 paise must qualify. `>` excludes the boundary.
- **Fix:** use `>=`.
- **Failing test:** "discount applies exactly at threshold". Lesson: boundary values are where tests should live; the suite had 99_999 and 200_000 but not 100_000 until this test.

## Bug 2: rounding error in tax (caught by a test)

- **Where:** `PriceCalculator.tax`, per-line integer division `lineNet * 18 / 100`.
- **Why:** integer division truncates, and doing it per line loses up to 1 paise *per line*. The spec says round half-up once on the whole order. 999 paise at 18% is 179.82, which should be 180, but the code gives 179.
- **Fix:** compute net = subtotal - discount, then `(net * 18 + 50) / 100`. The `+ 50` makes integer division round half-up. The signature becomes `tax(long netPaise)`; update `total`.
- **Failing test:** "tax is rounded half-up on the whole order". Extra test worth adding: two lines of 999 paise (per-line rounding vs whole-order rounding differ).

## Bug 3: idempotency check uses the wrong key (caught by a test)

- **Where:** `PaymentCallbackHandler.onPaymentSucceeded`, `processedPaymentIds.contains(orderId)` while the set stores `paymentId`.
- **Why:** the lookup key never matches what is stored, so a gateway retry of the same payment is applied again and the paid amount doubles.
- **Fix:** record and check the same key, atomically: `if (!processedPaymentIds.add(paymentId)) return DUPLICATE;`. `Set.add` returns false if present, so check and record is one step. Doing `contains` then `add` separately would leave a small race between two simultaneous retries.
- **Design note:** in production the processed IDs live in a database with a unique constraint, in the same transaction as the balance update; otherwise a crash between the two steps loses or repeats the payment. Also consider: if the order is unknown, should the id be recorded? (The sample fix records it only for known orders.)
- **Failing test:** "duplicate payment callback is applied once".

## Bug 4: check-then-act race in inventory (NOT caught by the suite)

- **Where:** `InventoryService.reserve`: `available(sku) < quantity` is read under the lock, the lock is released, then a second lock acquisition does the decrement.
- **Why:** two threads can both see "1 left", both pass the check, both decrement: stock goes negative / oversold. Each step is individually locked, but the *check and the act* are not one atomic unit. The sequential tests never interleave threads, so they pass.
- **Fix:** take the lock once around both the check and the update (see the corrected `reserve`: lock, read `stock.getOrDefault`, return false if short, else `merge`, unlock in `finally`).
- **Wrong fixes an AI may suggest:** marking only `available` synchronized (same race); removing the lock and using `ConcurrentHashMap` without `compute` (still check-then-act); a `volatile` field (visibility is not atomicity).
- **The test you must add** (fails before, passes after; it failed in all 3 runs here with 101–103 successes):

```java
test("concurrent reserve never oversells", () -> {
    InventoryService inv = new InventoryService();
    inv.addStock("a", 100);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    List<Thread> ts = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
        Thread th = new Thread(() -> {
            try { go.await(); } catch (InterruptedException e) { return; }
            for (int j = 0; j < 50; j++) if (inv.reserve("a", 1)) ok.incrementAndGet();
        });
        ts.add(th); th.start();
    }
    go.countDown();                       // release all threads at once
    for (Thread th : ts) th.join();
    assertEquals(100, ok.get(), "successful reservations");
    assertEquals(0, inv.available("a"), "stock left");
});
```

Concurrency tests are probabilistic: a failure proves the bug, a pass proves little. That is why you also reason about the code. Add the imports `java.util.*` and `java.util.concurrent.*` (and `atomic.AtomicInteger`).

## Smaller things a strong candidate may mention (not planted bugs)

- `Order.applyPayment` marks PAID only when `paid >= total`; overpayment is silently accepted.
- `OrderService.cancel` has a check-then-act on status too (two concurrent cancels could release stock twice). `Order.cancel` should return whether it changed state.
- `placeOrder` is not atomic across threads against crashes (stock reserved, order not saved).

## Feature A: coupons with expiry (sample notes)

- Model `Coupon(code, type, value, Instant expiresAt)`; a `CouponRepository` or a `Map` in the calculator.
- Inject a `java.time.Clock` into the code that validates coupons. Tests use `Clock.fixed(...)`; no `Thread.sleep`.
- Decide and state the order: subtotal, then threshold discount, then coupon, then tax (tax applies to the final net). Say you chose "no stacking" or "stack" and why; either is fine if stated.
- Expiry boundary: is `now == expiresAt` expired? Pick one (`!now.isBefore(expiresAt)` means expired at the instant) and test it, same lesson as Bug 1.
- Clamp so the total is never negative for a flat coupon. Keep money in paise; percentages use integer math with the same half-up helper.
- Tests: valid, expired, exactly at expiry, unknown code, flat larger than subtotal, coupon + threshold discount.

## Feature B: partial refunds (sample notes)

- Add `refundedPaise` to `Order` and a `Set<String>` of processed refund ids (same idempotency pattern as Bug 3, keyed by `refundId`, check-and-record with `add`).
- Rule: `amount > 0` and `amount <= paidPaise - refundedPaise`, else throw. Do the check and the update under the order's lock (`synchronized` method on `Order`) so two concurrent refunds cannot overshoot, which is Bug 4's pattern again.
- Status: add `PARTIALLY_REFUNDED` and `REFUNDED` (refunded == paid), or keep PAID plus a separate amount. State the trade-off: more statuses mean more transitions to test.
- Retried refund with the same id returns the original result, not an error.
- Tests: partial, exact full, over-refund, refund of an unpaid order, duplicate refund id, concurrent refunds.

## Verified results

- Before fixes: 10 passed, 3 failed (discount boundary, tax rounding, duplicate payment). With the concurrency test added: 10 passed, 4 failed.
- After the four fixes above (on a scratch copy): 14 passed, 0 failed.
