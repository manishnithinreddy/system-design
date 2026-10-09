# Practice kit: AI-assisted coding on an existing codebase

A self-run rehearsal of the round described in [Interviews in the AI era, section 4.1](../../guides/interviews-in-the-ai-era.md): you get a small multi-file Java codebase with failing tests, you fix it, then you add features, and you talk through your decisions. The format is as reported by candidates and blogs (the guide marks how well each claim is sourced); treat it as a realistic guess, not a spec.

> 💡 **Codebase round** = instead of a blank editor, you get a repo of 5–10 files. Reading code you did not write, and judging changes (yours or an AI's), is the skill being tested. Think of it as on-call debugging in a service you have never seen.

## What the round looks like (as reported)

- 45–60 minutes, a real IDE or a browser editor, and **an AI assistant you may use** at some companies (it is a pilot at others; ask your recruiter).
- A few failing tests, then a small feature. The interviewer watches *how* you work, not only whether tests go green.
- You are expected to think aloud. Silence while the AI types is a bad sign.

## Rules for practising

1. **Timebox 60 minutes** (set a timer): 10 min reading and running tests, 25 min bugs, 20 min feature, 5 min wrap-up.
2. Do it twice if you can: once **with** an AI assistant (any chat or IDE tool), once **without**. Compare what slowed you down.
3. **Narrate out loud** (or type notes in a scratch file): what you think the bug is, why, and how you will check.
4. Do not open [SOLUTIONS.md](SOLUTIONS.md) until you have finished or the timer ended.
5. Java 21, plain `javac`, no build tool, no libraries. Edit only the files under `src/orders/`.

## Run the tests

```bash
cd practice/ai-assisted-coding
./run.sh
```

`run.sh` compiles into a temporary folder (nothing is written into the repo) and runs `orders.Tests`, a tiny test runner in plain Java (no JUnit). Exit code is 1 if any test fails. You should start with **3 failing tests**.

## The codebase

| File | Job |
|---|---|
| `LineItem` | one SKU, unit price and quantity; money is **paise as `long`** (1 rupee = 100 paise) |
| `PriceCalculator` | subtotal, discount, tax (GST, a sales tax) and total |
| `InventoryService` | stock per SKU, `reserve` / `release`, guarded by a lock |
| `PaymentCallbackHandler` | webhook (an HTTP call from the payment gateway to us) saying "payment succeeded" |
| `OrderRepository` | in-memory map standing in for a database |
| `Order`, `OrderService` | the order and the entry point that ties everything together |
| `Tests` | the test runner and tests (read it first) |

## The brief (read this aloud like an interviewer would)

> "Our orders service has user complaints: customers who spend exactly Rs 1000 miss their discount, some totals are one paise off, and finance sees orders marked paid twice as much as they should be. Get the failing tests green. Then tell me whether you trust the rest of the code, and add tests for anything the suite does not cover. When that is done, add two features:"

**Task A: coupons.** `PriceCalculator` (or a new class) accepts a coupon code with a percentage or flat discount and an **expiry time**. Expired coupons are rejected with a clear error. Think: does it stack with the 10% discount? Where does the current time come from, so you can test expiry without sleeping?

**Task B: partial refunds.** Add `refund(orderId, refundId, amountPaise)`. A refund cannot exceed what was paid minus what was already refunded. Retried refund requests must not refund twice. Think: which status does the order have afterwards?

## Grading rubric (score yourself 0–2 per row)

| Area | 0 | 1 | 2 |
|---|---|---|---|
| **Understand before changing** | edited on first guess | read the failing test only | read the tests, the callers, and ran the suite before touching code |
| **Tests** | made tests pass by editing the test | fixed bugs only | added a test that fails first, then passes, for any bug the suite missed, and for each feature |
| **Verifying AI suggestions** | pasted and accepted | skimmed the diff | read every line, ran tests after each change, rejected at least one suggestion with a reason |
| **Communication** | silent or vague | described what, not why | stated a hypothesis, how to confirm it, trade-offs, what you did not do |
| **Scope and root cause** | patched symptoms, big rewrites | fixed bugs, small side changes | minimal, targeted diffs; named the root cause class (off-by-one, race, idempotency, rounding) |

10 points is excellent, 7–8 is a pass-level run, below 5 means repeat the exercise.

## What good looks like when steering an AI

1. **Read first, prompt second.** Run `./run.sh`, read the failures, skim every file. Only then ask.
2. **Ask it to explain before it edits**: "Walk me through `reserve()`. What can go wrong if two threads call it?" Check its explanation against the code.
3. **Ask narrow questions**: "what calls `applyPayment`?" beats "fix everything".
4. **Check the diff.** Look for deleted lines, removed locks, loosened assertions, and changed tests. A plausible but wrong fix is common, for example making a test pass by lowering the expected value.
5. **Run the tests after every change**, not at the end.
6. **Reject wrong suggestions out loud**: "this makes `reserve` synchronized but `available` is still read outside the lock; that keeps the race."
7. **Own the code.** You must be able to explain every line you leave in, as if on-call for it tomorrow.

## Before you start: refresh these

- [Thread safety basics](../../LLD/concepts/thread-safety-basics.md): check-then-act races and locks.
- [Idempotency and delivery semantics](../../HLD/concepts/idempotency-and-delivery-semantics.md): why webhooks are retried and how to dedupe.
- Related prep: [code review interview guide](../../guides/code-review-interview.md).

## After you finish

Open [SOLUTIONS.md](SOLUTIONS.md). Note which bugs you found, which one you only found by writing a test, and whether the AI (if used) ever led you wrong.
