# The code review interview

> Part of the prep series started in [Interviews in the AI era](interviews-in-the-ai-era.md) (section 4.3). Hands-on partner: [AI-assisted coding practice kit](../practice/ai-assisted-coding/README.md). Reported by candidates and blogs, not published policy at big companies, so treat the format as likely, not guaranteed.

## 1. What the round is

You are shown a pull request (PR: a proposed code change shown as a diff, the lines removed and added) and asked: "Review this as you would at work." The author may be a teammate or an AI assistant; with AI-written code the failure modes are the same, but the code looks more confident than it is.

What the interviewer scores:

- Did you find the **serious** problems (data loss, double charge, outage), not just style?
- Did you **prioritise** and say what blocks the merge?
- Were your comments **clear and kind**, with a suggested fix?
- Did you ask about **context** before judging (traffic, who calls this, what the tests cover)?

> 💡 You already do this on-call: reading a diff and asking "what breaks at 3am?" is the same instinct. A good review is a pre-mortem (imagining the incident before it happens).

## 2. How to run the 30 minutes

1. **Read the PR description and tests first** (2 min). What is it supposed to do? Say it back in one sentence.
2. **Skim the whole diff** once for shape, then go slow on the risky parts: anything touching money, shared state, retries, queries, or deletes.
3. **Ask clarifying questions out loud**: "Is this endpoint called by one client or many? Is the downstream call idempotent?"
4. **Collect comments, then rank them.** Say the top 2–3 first, then the rest quickly.
5. **Finish with a verdict**: approve, approve with nits, or request changes, and what would change your mind.

## 3. Checklist, ordered by importance

Work top to bottom. Stop going deeper into a category when you have found a blocker there; widen instead.

| # | Area | Questions to ask | Typical finding |
|---|---|---|---|
| 1 | **Correctness** | Does it do what the description says? Edge cases: empty, null, boundary values, time zones, integer overflow, rounding? | off-by-one, wrong key, money in `double` |
| 2 | **Concurrency** | Is state shared between threads? Is a check followed by an action without one lock? Is a cache or map thread-safe? See [thread safety basics](../LLD/concepts/thread-safety-basics.md). | check-then-act race, unsafe lazy init |
| 3 | **Security** | Is user input concatenated into SQL, shell, or HTML? Are secrets logged? Is authorisation checked, not just login? | SQL injection, IDOR (reading another user's record by guessing its id) |
| 4 | **Error handling and idempotency** | What if the call fails halfway? Is a retry safe? Are exceptions swallowed? See [idempotency](../HLD/concepts/idempotency-and-delivery-semantics.md) and [retries and backoff](../HLD/concepts/retries-backoff-and-dlq.md). | double charge on retry, empty `catch`, partial writes |
| 5 | **Performance and scale** | Does it run a query per item (the "N+1" pattern)? Is there an index for the filter? Unbounded lists or caches? Work done while holding a lock? | missing index, unbounded memory |
| 6 | **Operability** | Metrics, logs with ids, timeouts, feature flag, rollback plan? | no timeout on an outbound call |
| 7 | **Readability and tests** | Names, size, duplicated code; a test for each behaviour change, including the failure path? | untested error branch, misleading name |

> 📝 Interviewers usually plant 3–5 issues of different kinds. If you only find style problems, you have missed the point of the round.

## 4. How to communicate comments

### Severity labels

Prefix each comment so the author knows what blocks the merge.

| Label | Meaning | Example |
|---|---|---|
| **blocker** | must fix before merge: bug, data loss, security | "blocker: this retries a POST that charges the card; needs an idempotency key" |
| **major** | should fix, could be a follow-up if agreed | "major: no index on `user_id`, this scans the table" |
| **minor** | improves quality, author decides | "minor: extract the TTL into a constant" |
| **nit** | taste, never blocks | "nit: `data` is vague, maybe `profile`" |
| **question** | I don't understand, not a verdict | "question: why is the TTL 24h here?" |

### Ask vs tell

- **Tell** when you are sure and the cost of being wrong is high: "This builds SQL from user input; use a prepared statement."
- **Ask** when you lack context or it is a judgement call: "Do we expect more than 10k rows here? If so a pagination limit would help."
- Say **why** in one sentence (the consequence), then offer **a fix**. "This will double charge" beats "this is wrong".
- Praise one thing that is good. It is real review hygiene, and shows judgement.
- Do not rewrite the author's design in comments; if the approach is wrong, say so once and propose a quick call.

### Example of one good comment

> **blocker** `PaymentClient.charge` retries up to 5 times on any exception, including timeouts. A timeout does not mean the charge failed, so a retry can charge twice. Suggest: send an idempotency key (same key on every attempt) and retry only on connect errors and 5xx, with exponential backoff.

## 5. Practice PRs

Try each one for 5–8 minutes before opening the answer. Write your comments with a severity label. The answers list what a strong candidate raises, most important first.

### PR 1: "Cache user profiles to cut database load"

Description: *Profile reads are 90% of our traffic. Cache them in memory for 1 hour.*

```diff
 public class ProfileService {
     private final ProfileRepository repository;
+    private final Map<String, Profile> cache = new HashMap<>();

     public ProfileService(ProfileRepository repository) {
         this.repository = repository;
     }

     public Profile getProfile(String userId) {
-        return repository.findById(userId);
+        Profile cached = cache.get(userId);
+        if (cached != null) {
+            return cached;
+        }
+        Profile profile = repository.findById(userId);
+        cache.put(userId, profile);
+        return profile;
     }

     public void updateProfile(String userId, ProfileUpdate update) {
         repository.update(userId, update);
     }
 }
```

<details><summary>Answer</summary>

1. **blocker: stale data.** `updateProfile` writes to the database but never invalidates or updates the cache, so readers keep the old profile forever. The description says "1 hour" but there is **no expiry code at all**; entries live until restart. Fix: a TTL (time to live: how long an entry may be served) plus invalidating the key on update. See [caching strategies](../HLD/concepts/caching-strategies.md) (cache-aside: read from cache, on miss read the database and fill the cache; delete the key on write).
2. **blocker: not thread-safe.** A plain `HashMap` mutated from many request threads can corrupt itself or lose entries. Use `ConcurrentHashMap` or, better, a bounded cache library (such as Caffeine) with a TTL.
3. **major: unbounded memory.** One entry per user ever read, no maximum size and no eviction (removing old entries). A big user base becomes an out-of-memory crash.
4. **major: multiple instances.** With several app servers, each has its own cache: an update on server A leaves server B stale. Ask whether staleness is acceptable (and for how long), or use a shared cache or an invalidation message.
5. **major: null handling.** If the user doesn't exist, `put(userId, null)` on `HashMap` is allowed but `ConcurrentHashMap` would throw; also a missing user will hit the database on every call (no negative caching). Decide on purpose.
6. **minor: stampede.** When a hot key is missing, many threads load it at once. `computeIfAbsent` or a loading cache collapses them.
7. **minor: no tests / metrics.** No test for read-after-update, and no hit-rate metric to show the cache helps.

Verdict: request changes.
</details>

### PR 2: "Retry failed payment calls"

Description: *Payment gateway is flaky, so retry until it works.*

```diff
 public class PaymentClient {
     private final HttpClient http;

     public PaymentResult charge(String orderId, long amountPaise) {
-        return http.post("/charge", new ChargeRequest(orderId, amountPaise));
+        while (true) {
+            try {
+                return http.post("/charge", new ChargeRequest(orderId, amountPaise));
+            } catch (Exception e) {
+                log.warn("charge failed, retrying");
+            }
+        }
     }
 }
```

<details><summary>Answer</summary>

1. **blocker: double charge.** The call is not idempotent (safe to repeat with the same effect). A timeout or dropped response means the gateway may have charged the card; the retry charges again. Send an idempotency key (for example derived from `orderId`) on every attempt so the gateway dedupes. See [idempotency and delivery semantics](../HLD/concepts/idempotency-and-delivery-semantics.md).
2. **blocker: infinite loop.** `while (true)` has no maximum attempts or overall deadline. During a gateway outage, request threads pile up forever and take the service down with them. Cap attempts (for example 3) and total time.
3. **blocker: no backoff or jitter.** Immediate retries hammer a struggling gateway (a retry storm) and all clients retry in lockstep. Use exponential backoff with random jitter. See [retries, backoff and DLQ](../HLD/concepts/retries-backoff-and-dlq.md).
4. **major: retries everything.** `catch (Exception e)` also retries permanent errors such as 400 "card declined" or a bug (`NullPointerException`). Retry only transient failures (connect errors, timeouts, 502/503/429), and honour `Retry-After`.
5. **major: swallowed cause and no timeouts.** The log omits the exception and attempt number. Set connect and read timeouts, otherwise one hung call stalls the loop.
6. **major: interrupt handling.** If a sleep is added for backoff, `InterruptedException` must restore the interrupt flag and stop.
7. **minor: where do failures go?** After the cap, what does the caller see? Define a failed result; consider a circuit breaker (stop calling a failing dependency for a while) or a dead-letter queue for later reconciliation.
8. **minor: tests.** Test: first call times out, second succeeds, only one charge exists (with a fake gateway).

Verdict: request changes. Strong candidates say the idempotency point first.
</details>

### PR 3: "Add order search by customer email"

Description: *Support wants to search orders by customer email and status.*

```diff
 public class OrderDao {
     private final Connection connection;

+    public List<Order> search(String email, String status) throws SQLException {
+        String sql = "SELECT * FROM orders WHERE customer_email = '" + email + "'"
+                   + " AND status = '" + status + "' ORDER BY created_at DESC";
+        List<Order> result = new ArrayList<>();
+        Statement st = connection.createStatement();
+        ResultSet rs = st.executeQuery(sql);
+        while (rs.next()) {
+            result.add(mapRow(rs));
+        }
+        return result;
+    }
 }
```

```diff
+-- migration V42
+ALTER TABLE orders ADD COLUMN customer_email VARCHAR(255);
```

<details><summary>Answer</summary>

1. **blocker: SQL injection.** The query is built by string concatenation, so an `email` of `' OR '1'='1` returns every order, and worse input can modify data. Use a `PreparedStatement` with `?` placeholders; the database then treats values as data, never as SQL.
2. **blocker: resource leak.** `Statement` and `ResultSet` are never closed, and a failure leaks the connection's cursors. Use try-with-resources (Java syntax that closes things automatically).
3. **major: missing index.** Filtering on `customer_email` (and `status`, sorting by `created_at`) with no index scans the whole table on every search. Add a composite index such as `(customer_email, status, created_at DESC)`, created concurrently or in a way that does not lock a large production table.
4. **major: unbounded result.** A customer with 100k orders returns them all into memory. Add `LIMIT` and pagination (keyset: `WHERE created_at < ?` beats big `OFFSET`).
5. **major: migration safety.** The new column is added but there is no backfill for old rows, and the nullable column gives empty results for them. Consider the rollout order: migrate, backfill, deploy code.
6. **major: authorisation and privacy.** Who may search by email? Email is personal data; check the caller's role and avoid logging it.
7. **minor: `SELECT *`** pulls columns you don't need and breaks when the schema changes; list the columns. Normalise email case (`LOWER`) consistently, and validate `status` against the enum instead of passing a free string.
8. **minor: tests.** One with a malicious string, one with no matches, one with many rows.

Verdict: request changes. Mention injection first, then the index.
</details>

## 6. Reviewing AI-written code

AI-generated PRs often compile, look tidy, and have these problems:

- **Plausible but wrong**: right shape, subtly wrong boundary, key, or unit.
- **Tests that prove nothing**: they assert what the code does, not what it should do, or mock away the part that matters.
- **Silent deletions**: a lock, a validation, or a failing test "cleaned up".
- **Invented APIs**: methods or config flags that do not exist in your version. Compile and run it.
- **Over-engineering**: new abstraction for a three-line change.

Ask the same questions as for a human: what is this change for, how do I know it works, what happens when it fails?

## 7. Common mistakes in the round

| Mistake | Better |
|---|---|
| 20 style nits, no real bug found | hunt for correctness, concurrency, security first |
| Rewriting the PR in comments | state the problem and a minimal fix |
| Everything is a blocker | use severity labels |
| Never asking about context | 2–3 questions up front |
| Harsh tone ("this is bad") | describe the consequence, not the person |
| Missing the missing: no tests, no timeout, no rollout plan | check what is absent, not only what is added |

## 8. Related

- [Interviews in the AI era](interviews-in-the-ai-era.md), [AI-assisted coding practice](../practice/ai-assisted-coding/README.md)
- [Idempotency](../HLD/concepts/idempotency-and-delivery-semantics.md), [retries and backoff](../HLD/concepts/retries-backoff-and-dlq.md), [caching](../HLD/concepts/caching-strategies.md), [thread safety](../LLD/concepts/thread-safety-basics.md)
