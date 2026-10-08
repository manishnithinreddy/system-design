# News Feed — L6 (Staff) Interview

> **Level expectation:** the L5 architecture is assumed and summarised quickly. The staff conversation is about what the feed is *for* and how it's run: ranking objectives and their side effects, experimentation, integrity, degradation strategy, privacy and deletion guarantees, multi-region, and cost. You show judgement about trade-offs that aren't purely technical. Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. What is the feed optimising for?

**🧑‍💼 Interviewer:** Design the news feed.

**🧑‍💻 Candidate:** Before architecture: the ranking objective is the most consequential design decision here, and it's not purely technical.

| Objective | Short-term effect | Risk |
|---|---|---|
| Maximise engagement (likes, comments, time spent) | Metrics go up fast | Rewards outrage and clickbait; user regret; regulatory scrutiny |
| Chronological only | Predictable, transparent | Users miss posts from close friends buried under frequent posters |
| Weighted mix: "meaningful interactions" (comments from friends, replies), plus penalties for hides/reports | Better long-term retention and satisfaction | Harder to measure; needs surveys and long-running experiments |

**🧑‍💻 Candidate:** I'd design the system so the objective is a **configurable, versioned scoring policy** owned with product and integrity teams, not hard-coded in the ranker. And I'd keep a **chronological "Following" view** always available, which is cheap with our architecture (it's the unranked candidate list) and increasingly expected by regulators (e.g. the EU's Digital Services Act requires very large platforms to offer a feed option not based on profiling).

> 📝 **Note:** Staff engineers are expected to surface the product/ethical consequences of a technical design, and to build the system so those decisions can change without a rewrite.

---

## 2. Experimentation is part of the architecture

**🧑‍💻 Candidate:** Ranking changes ship as experiments, constantly. The platform needs:
- **Assignment:** deterministic bucketing by `userId` (hash → bucket), so a user stays in one variant.
- **Per-request experiment context** flowing through candidate generation → ranking → re-rank, logged with every impression.
- **Impression + interaction logging** (what was shown, at which position, what the user did) → the training data for the next model and the metrics for the experiment.
- **Guardrail metrics** checked automatically: latency, crash rate, report/hide rates, time to first post. Experiments that breach guardrails auto-stop.
- **Long-term holdouts:** a small % of users kept on an old ranking for months, to measure slow effects (retention, regret) that short tests miss.

The cost is real: impression logs are the biggest data stream in the system, often larger than posts and likes combined. They need a sampling and retention policy.

---

## 3. Degradation ladder

**🧑‍💻 Candidate:** The feed should almost never be an error page. Decide the ladder in advance:

| Level | Trigger | What users get |
|---|---|---|
| 0. Normal | — | Personalised ranked feed |
| 1. Light ranking | Ranker latency/error budget burning | Cheaper model or cached scores |
| 2. Chronological | Ranker down | Candidates by time (still personalised by follow graph) |
| 3. Stale | Feed cache/candidate generation impaired | Last served ranked list for the session, or the last cached feed |
| 4. Static | Large outage | Popular/regional posts from a CDN-cached list |

Each level has an owner, a switch (feature flag), and is exercised in **game days**: planned exercises where you deliberately break a dependency in production-like conditions to check the fallback actually works.

---

## 4. Integrity and abuse

- **Ranking is an attack surface:** fake likes/follows (bot farms) to boost posts. Counters must distinguish "trusted" interactions: weight by account age/reputation; detect coordinated bursts.
- **Integrity signals in re-ranking:** classifier scores (spam, misinformation labels, graphic content) demote or remove before display. The pipeline must fail **closed** for removals (if the integrity service is down, don't show content already flagged for removal) and **open** for demotions.
- **Fan-out of harmful content:** when a post is removed after going viral, read-time filtering (L5) makes removal effective immediately across all precomputed feeds. That's the strongest argument for that design.

---

## 5. Privacy and deletion guarantees

- **Account deletion / GDPR erasure:** posts, likes, comments and follow edges must disappear from serving within a short window (minutes for visibility) and from all storage, backups and derived data (features, training sets) within legal timelines (e.g. 30 days, depending on jurisdiction). Requires a **deletion pipeline** that knows every store that holds user data, which is an inventory problem as much as a technical one.
- **Privacy changes** (account goes private): read-time checks enforce immediately; precomputed feeds are cleaned lazily.
- **Close friends / audience lists:** a post's audience is checked at hydration time, so precomputed feeds can't leak a restricted post to someone removed from the list.

---

## 6. Multi-region

- Users read from the **nearest region**. Feed caches are **regional and rebuildable**: don't replicate 3 TB of feed lists across regions; replicate the **sources** (posts, follow graph) and let each region fan out locally from a replicated post-event stream.
- A post by a user in India reaches a follower in the US via cross-region replication of the post + local fan-out: a few seconds extra, within the freshness SLO.
- Celebrity recent-posts lists are tiny and extremely hot: replicate everywhere.
- Writes (posts, likes) go to the author's home region, or to a multi-region store with conflict-free counters for likes ([CAP & consistency](../../concepts/cap-and-consistency.md)).

---

## 7. Cost

| Cost driver | Rough size | Lever |
|---|---|---|
| Media storage + CDN egress | Biggest bill (PB of images/video) | Compression, modern formats, tiered storage for old media |
| Ranking inference | Thousands of model evaluations per feed load | Two-stage ranking (cheap model prunes before the expensive one), caching scores per session |
| Feed cache memory | ~3 TB × replicas × regions | Skip inactive users, shorter lists, rebuild-on-demand |
| Impression logging | Largest event stream | Sampling, retention tiers |

**🧑‍💻 Candidate:** A staff-level habit: attach a **cost per 1,000 feed loads** to the architecture and track it like latency. Ranking changes that improve engagement 0.5% but double inference cost need that number in the decision.

---

## 8. Curveballs

**🧑‍💼 Interviewer:** A celebrity's post isn't showing up for some followers.

**🧑‍💻 Candidate:** Celebrity posts are pulled, so check: (a) is the account above the threshold and in the follower's "celebrities I follow" set, which is a cache that can be stale after someone crosses the threshold (the transition is the tricky moment: switch new posts to pull, keep old pushed ones); (b) was it filtered: seen-filter false positive, integrity demotion, audience restriction; (c) regional replication lag of the post. Impression/debug logs that record *why* each candidate was dropped make this answerable in minutes instead of days, so I'd build that explainability in from the start.

**🧑‍💼 Interviewer:** Product wants "real-time" feeds: posts appear without refreshing.

**🧑‍💻 Candidate:** Most of the value is in a "New posts ↑" pill, which needs only a lightweight "new items exist" signal: a periodic check of the head of the user's feed list, or a push via an existing realtime connection if the app already has one (as with [chat](../chat-system/README.md)). Inserting posts into the visible list while someone reads is a bad UX, and holding a WebSocket per feed viewer for this alone is a lot of infrastructure for little benefit.

---

## 9. What the interviewer was evaluating (L6)

- [ ] Surfaced the ranking objective as a product/ethics decision and made it configurable; kept a chronological option
- [ ] Experimentation as architecture: assignment, logging, guardrails, holdouts
- [ ] A pre-decided degradation ladder with switches and game days
- [ ] Integrity: abuse of signals, fail-closed removals, read-time enforcement
- [ ] Deletion/privacy guarantees across serving, storage and derived data
- [ ] Multi-region: replicate sources, rebuild regional caches
- [ ] Cost per feed load as a tracked metric
- [ ] Debuggability ("why wasn't this post shown?") designed in

## 10. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| Treating ranking as a pure ML detail | It's the product's most consequential and scrutinised decision |
| No fallback below "ranked feed" | Ranking incidents become outages |
| Replicating precomputed feeds across regions | Huge cost for data you can rebuild locally |
| Deletion handled only in the main DB | Data lingers in caches, features, training sets, backups |
| No impression logging plan | Can't train, can't measure, can't debug |
| Ignoring cost | Media and inference bills dominate; decisions without them are incomplete |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
