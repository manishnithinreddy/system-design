# Start Here: What Is a Rate Limiter? (Before the Interview)

> You've almost certainly been rate-limited already, probably without knowing the name. This page connects the idea to things you've experienced as a user and as an infra engineer, so every term in the interviews (bucket, window, burst, key, 429…) has a picture behind it.
>
> Time: ~10 minutes. Then go to the interviews: [L4](L4-mid.md) → [L5](L5-senior.md) → [L6](L6-staff.md).

---

## 1. You've been rate-limited before

| Situation | What you saw | What was really happening |
|---|---|---|
| Typing a wrong password 5 times | "Too many attempts. Try again in 15 minutes." | A limit of **5 login attempts per 15 minutes per account**, to stop password guessing |
| Requesting an OTP repeatedly | "Resend OTP in 30 seconds" (button greyed out) | **1 OTP per 30 seconds per phone number**. SMS costs money and can be abused to spam someone |
| Ticket booking at sale time / flash sales | "Too many requests, please try again" | The site protects its servers from millions of refresh clicks |
| Calling a public API in a script (GitHub, Twitter, OpenWeather…) | Error `429 Too Many Requests` after N calls | **API quota**, e.g. free tier = 60 requests/hour |
| As an infra engineer: AWS CLI / Terraform | `ThrottlingException: Rate exceeded` | AWS limits API calls per account per second |
| Kubernetes | `kubectl` slowness, "client-side throttling" log lines, API Priority & Fairness | The kube-apiserver and client-go both rate-limit to protect the control plane |
| nginx / Envoy / API gateway config | `limit_req zone=... rate=10r/s burst=20` | You may have *configured* a rate limiter without calling it that |

**A rate limiter answers one question, extremely fast, for every request:**

> "Has **this client** made **too many requests** **recently**? → allow or reject."

---

## 2. Why systems need it

| Reason | Story |
|---|---|
| **Protect the servers** | One buggy client script calls your API in an infinite loop at 5,000 requests/second. Without a limit, your database melts and *every* customer has an outage because of one. |
| **Fairness** | 10,000 customers share the service. One big customer shouldn't be able to use 90% of capacity. |
| **Security** | Password guessing, OTP brute force, scraping all products/prices. Attacks need *many* requests, and limits make them impractically slow. |
| **Cost control** | Every OTP SMS costs money, and every call to a paid downstream API costs money. Limits cap the bill. |
| **Business plans** | Free plan: 100 requests/minute. Pro plan: 10,000/minute. The limit *is* the product tier. |

---

## 3. The vocabulary, through examples

### Key: limit *whom*?
The limit is counted **per something**. That something is the **key**.

| Key | Example | Watch out |
|---|---|---|
| User ID | "Each logged-in user: 100 requests/min" | Doesn't work before login |
| API key | "Each developer app: 5,000/hour" (GitHub, Stripe) | Most common for APIs |
| IP address | "Each IP: 20 login attempts/min" | Many users behind one office/college/mobile NAT share an IP. Attackers rotate IPs |
| Phone number / account | "Each phone: 1 OTP per 30 s" | Business-level limits |

Each key gets its **own** counter, so you being blocked doesn't block me.

### Limit and window: "N requests per T"
`100 requests per 1 minute` → limit = 100, window = 1 minute.

### Burst: many requests at once
Opening a web app's dashboard might fire **15 API calls in 1 second**, then nothing for a minute. That's a **burst**. Should "100 per minute" allow 15 in one second? Usually **yes**, because that's normal behaviour. But should it allow all 100 in the first second? Maybe not. *How an algorithm treats bursts is the main difference between algorithms.*

### What happens when you're over the limit
Usually: **reject immediately** with HTTP **`429 Too Many Requests`** and tell the client when to come back:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
```

(Alternatives: queue the request and process it later, or slow it down ("throttle"). These are less common for APIs.)

---

## 4. The four algorithms as everyday pictures

You'll implement these in the interviews. Here's the intuition first:

### 🪣 Token bucket: a bucket of coins that refills
You have a jar that holds at most **10 coins**. Every request costs 1 coin. A machine drops **1 coin every 6 seconds** into the jar (= 10 per minute). Jar full? Extra coins fall out.
- Been away for a while → jar is full → you can make 10 requests instantly (**burst allowed**).
- After that you're limited to the refill speed.
- Used by: AWS APIs, Stripe, most API gateways, nginx's `burst=`.

### 🗓️ Fixed window: a counter that resets on the clock
"Max 100 per calendar minute." A counter starts at 0 at 10:00:00, resets at 10:01:00.
- Simple: one number per client.
- Flaw: 100 requests at 10:00:59 + 100 at 10:01:00 = **200 requests in 2 seconds**, all allowed.

### 📜 Sliding window log: a notebook of timestamps
Write down the time of every request. For a new request, cross out entries older than 1 minute and count what's left.
- Perfectly accurate. No edge problem.
- But you keep up to 100 timestamps *per client*. Expensive for big limits.
- Good for small, strict limits: "5 login attempts per 15 minutes".

### 📊 Sliding window counter: fixed window, smoothed
Keep only *this* minute's count and *last* minute's count, and estimate the last 60 seconds as "a weighted mix of the two".
- Cheap like fixed window, nearly as accurate as the log.
- Used at very large scale (Cloudflare has written about this approach).

---

## 5. See it yourself on a real API (2 minutes)

GitHub's public API tells you your rate limit in every response:

```sh
curl -sI https://api.github.com/users/octocat | grep -i ratelimit
```

You'll see headers like:

```
x-ratelimit-limit: 60          ← your quota (unauthenticated: per IP, per hour)
x-ratelimit-remaining: 59      ← how many you have left
x-ratelimit-reset: 1791381572  ← Unix time when the window resets
x-ratelimit-used: 1
```

Run it a few times and watch `remaining` go down. Exceed it and you get `403`/`429` with a message saying you've hit the limit. That's a rate limiter answering "allow or reject" for the key **your IP address**. (Docs: GitHub REST API → "Rate limits for the REST API".)

Also worth reading for 5 minutes if you use them at work: the nginx `limit_req` docs (token-bucket-like with `rate` and `burst`), and Kubernetes "API Priority and Fairness".

---

## 6. From experience to requirements

| What you experienced | Requirement | Type |
|---|---|---|
| "Too many attempts" for *my* account only | Limit **per key**; keys are independent | Functional |
| Free vs paid API quotas | **Different limits** per client tier | Functional |
| "Try again in 30 s" | Reject with `429` + `Retry-After` | Functional |
| Dashboard fires 15 calls at once and works | **Allow reasonable bursts** | Functional (algorithm choice) |
| You never noticed the limiter on normal use | Must add almost **no latency** (it runs on *every* request) | Non-functional |
| 100 requests from 50 threads at once still allow exactly 100 | **Thread-safe / correct under concurrency** | Non-functional, and the core of the LLD interview |
| Millions of users, each with a counter | **Bounded memory** (forget idle users) | Non-functional |
| API runs on 50 servers but the quota is global | **Distributed** limit | Non-functional (the [L6](L6-staff.md) topic) |

---

## 7. Mini glossary

| Term | Meaning in plain words |
|---|---|
| **Rate limit** | Max number of requests allowed in a time period |
| **Key** | Who the limit applies to: user, API key, IP, phone… |
| **Window** | The time period: per second, minute, day |
| **Burst** | Many requests in a very short moment |
| **Token / permit** | One "allowed request" unit |
| **Throttling** | Often used loosely for rate limiting. Strictly means *slowing down* rather than rejecting |
| **429 Too Many Requests** | The HTTP status for "you're over the limit" |
| **Retry-After** | Response header telling the client how many seconds to wait |
| **Quota** | Usually a long-window limit: "10,000 requests per month" |
| **In-process vs distributed** | Limit counted inside one server's memory vs shared across many servers (e.g. via Redis) |

➡️ **Now you know what it is. Next, build it:** [L4-mid.md](L4-mid.md)
