# Software Engineering Interviews in the AI Era (2024–2026)

> **One line:** Since 2024, AI tools can solve a standard LeetCode problem in seconds, and invisible "overlay" apps feed answers to candidates during remote interviews. Companies split into two camps: **ban AI and verify harder** (in-person rounds, proctoring, custom questions) or **allow AI and test judgement** (work in a real codebase with an assistant, then explain and defend every line). Many big companies now do **both, in different rounds**. Fundamentals didn't go away; they moved from "can you produce code" to "can you tell whether code and designs are right."

This is a **guide**, not an interview. It answers the reader's question: "the prep in this repo is pre-AI-era style; what do interviews look like now?" Researched in October 2026 from public sources.

> 💡 **Words used a lot below.** **LLM** (large language model): the model behind ChatGPT, Claude, Gemini, Copilot. **AI assistant / coding agent**: a tool that writes or edits code from a text instruction (Copilot, Cursor, Claude Code). **LeetCode-style**: short algorithm puzzles (graphs, DP) solved alone in 45 minutes. **Onsite / loop**: the final set of 4–6 interviews. **Take-home**: a task done at home over hours or days. **Proctoring**: watching a candidate (webcam, screen, browser lock) to stop cheating. **CoderPad / HackerRank**: browser code editors that companies use for interviews.

## 0. How much to trust each fact

| Mark | Meaning |
|---|---|
| ✅ | The company's own blog, careers page, press release or an on-record spokesperson statement |
| 🟡 | Reputable press quoting internal documents, or prep sites / candidate reports (Glassdoor, Blind, PracHub). Directional, not policy |
| ❓ | Reported but I could not confirm; conflicting accounts |

> 💡 **Research note:** most company pages could not be opened directly from the research environment, so many facts come from search results quoting them. Policies change fast and differ by team, level and country: **the recruiter's email for your specific loop is the only source of truth.** Always ask: "Is AI allowed in this round?"

---

## 1. TL;DR

- **The trigger (2024–2025):** LLMs got good enough to solve standard interview puzzles, and tools like Interview Coder (2025) showed invisible on-screen help during remote interviews. Interviewers surveyed in 2025 widely suspected cheating ([Pragmatic Engineer, Sep 2025](#s16)).
- **Camp 1, "ban and verify":** Amazon warns candidates they can be disqualified for unpermitted AI use (2025); Anthropic says live interviews are "all you" unless told otherwise (2025); Google, Cisco and McKinsey brought back some in-person rounds (2025).
- **Camp 2, "allow and judge":** Canva *expects* AI tools in technical interviews (Jun 2025); Meta began testing an AI-assisted coding round (2025); DoorDash rebuilt its interviews around AI (Mar 2026); Google is piloting an AI-assisted "code comprehension" round (reported May 2026).
- **Most common real answer is "both":** one classic no-AI coding round to check fundamentals + one AI-assisted round in a **multi-file codebase** (read, debug, extend). Survey data still says most companies ban AI in interviews (Karat: ~two-thirds, Dec 2025).
- **New round shapes:** code comprehension/debugging in an existing repo, code review of (often AI-written) code, "build a small feature with an AI pair", paid work trials, and behavioural rounds that dig into a real past project.
- **System design changed less than coding,** but new prompts appear: "design a RAG chatbot", "design an LLM gateway with token budgets", "design semantic search", plus GPU/model-serving questions for infra roles. They reuse old building blocks (queues, caches, rate limiters, sharding).
- **What didn't change:** clarifying requirements, estimates with arithmetic, trade-offs, failure handling, concurrency, data modelling and clear communication. These are exactly what the AI-assisted rounds now grade, because typing code is cheap.
- **For an India-based candidate:** the same split shows up; Indian recruiters lean on AI proctoring plus live follow-up questions (🟡), and GCCs (global capability centres, i.e. Indian offices of foreign companies) increasingly list AI skills in job requirements (🟡).

---

## 2. What changed, and why

**1. Cheating became cheap and hard to see.** In 2025 a Columbia student, Roy Lee, built **Interview Coder**, a desktop app that screenshots the problem and shows an AI-generated answer in a window that screen-sharing doesn't capture. He recorded himself using it in an Amazon internship interview; Amazon reportedly complained to the university, and he was disciplined (accounts of the exact outcome differ ❓). He then raised a **$5.3M** seed round for **Cluely**, an "AI to cheat on everything" startup ([TechCrunch, Apr 2025](#s9); [NBC News, 2025](#s10)).

- 🟡 An interviewing.io experiment (2024) found that when interviewers asked **verbatim LeetCode** questions, candidates secretly using ChatGPT passed **73%** of the time and nobody was caught; fully **custom questions** were much harder to cheat on ([interviewing.io](#s15)).
- 🟡 In a 2025 survey of 63 interviewers, **81%** of Big Tech interviewers had suspected AI cheating and **31%** had caught someone ([Pragmatic Engineer, Sep 2025](#s16)).
- 🟡 Gartner (Jul 2025) predicted that by **2028, 1 in 4 candidate profiles worldwide could be fake**, and **6%** of 3,000 surveyed candidates admitted to interview fraud (impersonating or being impersonated) ([HR Dive, 2025](#s17)). This is a *forecast*, not a measurement.

**2. The job itself changed.** Companies push engineers to use AI daily, so a no-AI puzzle tests a different job. Canva said almost half its frontend and backend engineers were daily users of an AI coding tool (2025) ([Canva](#s1)). Google's CEO reportedly said ~75% of new code at Google is AI-generated and reviewed by engineers (reported 2026, 🟡) ([Yahoo Finance/BI, May 2026](#s5)). Meta's internal post said an AI-assisted round "is more representative of the developer environment that our future employees will work in, and also makes LLM-based cheating less effective" (🟡, [Business Today, Jul 2025](#s3)).

**3. The signal moved to judgement.** If the assistant writes the first draft, what separates engineers is: understanding unfamiliar code fast, spotting the subtle bug, choosing between two designs, testing, and owning the result. Canva's post says candidates must own any code they submit, whoever wrote it ([Canva, 2025](#s1)). DoorDash wrote that with AI, a standard prompt turns either into "proctoring or a shared pretense", so it chose interviews that match the job instead of an arms race over detection ([DoorDash, Mar 2026](#s7)).

> 💡 **Infra analogy:** it's like on-call after you adopted Kubernetes. Nobody hand-writes pod specs from memory any more, but you still have to know *why* the pod is in CrashLoopBackOff. The tool does the typing; you do the diagnosis.

---

## 3. Company-by-company

Policies differ **by round**, so "allows" usually means "allows in one designated round".

| Company | Stance | What the round looks like | Source + date | Confidence |
|---|---|---|---|---|
| **Canva** | Allows, in fact *expects* AI (Backend, ML, Frontend roles) | Candidate brings Copilot / Cursor / Claude, shares screen, solves open-ended practical problems and explains choices; fundamentals still assessed. Reported to replace the old "CS fundamentals" round 🟡 | [Canva eng blog, 11 Jun 2025](#s1); [ACS, 2025](#s2) | ✅ (format details 🟡) |
| **Meta** | Mixed: testing an AI-assisted round alongside a classic one | Meta spokesperson confirmed "testing how to provide these tools to applicants". Reported format: 60 min in CoderPad with a built-in assistant, a **multi-file codebase** with failing tests and partial features; replaces one of two onsite coding rounds (from ~Oct 2025). How wide the 2026 rollout is: ❓ | [404 Media / Business Insider via Business Today, Jul 2025](#s3); [interviewing.io guide, 2025](#s4) | ✅ that it exists; 🟡 format |
| **Google** | Changed format **twice**: more in-person (2025), then piloting AI-assisted (2026) | 2025: some rounds moved back in person; Pichai: "worth thinking about some fraction of the interviews being in person". 2026 pilot: "code comprehension" round where candidates "read, debug and optimize" existing code with **Gemini**; graded on prompting, output validation, debugging. Junior–mid roles, select US teams, H2 2026. Googleyness & Leadership round adds a technical design talk about your past work | [CNBC via CXO Digital Pulse, 2025](#s6); [Business Insider via Yahoo Finance, May 2026](#s5) | 🟡 (spokesperson confirmed Gemini use) |
| **Amazon** | Bans unless permitted | Candidates told: "please do not use GenAI tools during your interview unless explicitly permitted… may result in disqualification." Recruiters given tips to spot it (reading answers, eyes wandering, typing while being asked) | [Business Insider via GeekWire/ITPro, Feb–Mar 2025](#s8) | ✅ spokesperson statement; 🟡 internal guideline text |
| **Anthropic** | Bans in live rounds and take-homes unless told otherwise; allows for prep and polishing applications | "Create your first draft yourself, then use Claude to refine it" (applications). Take-homes: "without Claude unless we indicate otherwise". Live: "all you–no AI assistance unless we indicate otherwise" | [Anthropic candidate AI guidance, updated 10 Jul 2025](#s11); [Fortune, Jul 2025](#s12) (earlier stricter rule relaxed) | ✅ |
| **DoorDash** | Mixed: no AI in classic rounds; a designated AI round | Oct 2025 stance: no AI in live interviews unless invited; you may be asked how you used AI to prepare. Mar 2026: interviews rebuilt to "reflect the work our engineers actually do"; reported 60-min AI-assisted working session in starter code on your own machine, scored on verification, debugging and trade-offs, not on finishing 🟡 | [DoorDash careers blog, Oct 2025 and 19 Mar 2026](#s7) | ✅ (format 🟡) |
| **Shopify** | Allows AI in pair-programming round | 75–90 min pairing with an engineer; any AI tool allowed, but looking up a ready-made solution is not; graded on how you direct, check and clean up AI output | [Pragmatic Engineer, Aug 2025](#s16) (mentions Shopify adapting); [Hello Interview, 2025](#s18) | 🟡 |
| **LinkedIn** | Mixed (one AI-enabled + one classic coding round, reported) | CoderPad with AI panel; familiar problems (LRU cache, merging intervals) plus follow-ups; one candidate was told "you can't rely entirely on AI" | [Hello Interview; PracHub candidate report, 2025–26](#s18) | 🟡 anecdotal |
| **Atlassian** | Mixed, varies by role | Some 2026 candidates got an unfamiliar repo in HackerRank with a built-in coding agent, then debugged or added a feature; others had a classic loop. Named as a customer of Karat's AI-enabled interviews | [Karat press release, 10 Dec 2025](#s13); [Glassdoor/PracHub, 2026](#s19) | 🟡 |
| **Cisco** | Changed format (more verification) | Added in-person checks and background screening against fake candidates; AI allowed only when the company explicitly invites it | [WSJ via Axios, 12 Aug 2025](#s14) | 🟡 |
| **McKinsey** | Changed format | Brought back in-person interviews for some roles; says it shows judgement and creativity and reduces AI misuse | [WSJ via Axios, 12 Aug 2025](#s14) | 🟡 |
| **Cursor (Anysphere)** | Project-based | Role posting: short technical interviews, then an onsite where you build a small project with the team. Reports say AI tools are allowed and the onsite is paid; I could not confirm the AI rule for early screens ❓ | Cursor job posting (seen via search, 2026); prep-site reports | 🟡 |
| **Interview platforms** | Selling both camps | HackerRank: AI-assisted interviews with a "guarded" assistant for algorithm questions (no full solutions) and an agent mode for repo questions, plus a full chat transcript for interviewers. Karat "NextGen" (Dec 2025): multi-file projects with an AI assistant, led by a human interviewer. Indian recruiters use AI proctoring (Talview, Mercer Mettl, HackerEarth) 🟡 | [HackerRank help centre, 2026](#s20); [Karat, Dec 2025](#s13); [NewsBytes, 2025–26](#s21) | ✅ vendor docs |

**Reading the table:** the split is not "old companies ban, new companies allow". Google did both within a year. The common pattern for large companies in 2026 is **one classic round to prove you can think without help, one AI round to prove you can work with help**.

> 💡 **How common is "AI allowed"?** Still a minority. Karat's Dec 2025 release says almost two-thirds of companies prohibit AI in interviews, and fewer than 30% are updating assessments ([Karat](#s13)); a later Karat survey of 400 engineering leaders put it at **62%** banning (seen only secondhand 🟡). Treat any single percentage loosely; surveys disagree.

### India-specific signals (thin, mostly 🟡)

- 🟡 Recruiters at Indian IT services and product firms describe candidates using second screens, phones under the webcam and desktop overlays during virtual rounds; one anonymous Infosys engineer described having ChatGPT and Copilot open during a round ([The420, 2025](#s22)).
- 🟡 Indian campus and lateral hiring leans on **AI proctoring** (webcam, audio, screen tracking), with HackerEarth's CEO saying live follow-up questions ("explain your answer") catch shortcut users ([NewsBytes, 2025–26](#s21)).
- 🟡 Taggd's GCC report (2026) says nearly half of GCCs need AI capability for more than a quarter of open roles ([Taggd, 2026](#s23)). This is about *job requirements*, not interview format.
- ❓ I found **no official statement** from Flipkart, Razorpay, Swiggy, Zepto, TCS or Infosys on AI in interviews. Prep sites describe their loops as classic (DSA + system design + values). Indian-office loops of Google, Meta, Amazon, LinkedIn and Atlassian follow the global policies above, but pilots (like Google's) may start in the US first.

---

## 4. New round types (with examples)

### 4.1 AI-assisted coding in a multi-file codebase (Meta, Google pilot, DoorDash, LinkedIn, Atlassian)

**Interviewer:** "Here's a small inventory service: 6 files, 3 failing tests. Get the tests green, then add bulk reservations. Use the assistant however you like and talk me through it."

**Good looks like:** read the structure and run the tests *before* prompting; ask the AI narrow questions ("what calls `reserve()`?") instead of "fix everything"; read every diff; catch the AI's plausible-but-wrong change (e.g. it "fixes" a race by removing a lock); add a test for the new feature; explain trade-offs out loud. **Weak looks like:** pasting the whole task into chat, accepting a big diff unread, not being able to explain a line.

> 📝 This is basically an **LLD round in a real codebase**. The repo's LLD material (thread safety, state machines, interfaces) is exactly what lets you judge AI output.

### 4.2 Code comprehension / debugging round (Google pilot, DoorDash reports)

**Interviewer:** "Latency on this endpoint doubled after yesterday's deploy. Here's the diff and the service. What happened?"

**Good looks like:** form a hypothesis from the diff, confirm it with logs or a quick test, then fix the root cause and say how you'd catch it next time (a metric, an alert, a test). This is on-call debugging, which the reader already does.

### 4.3 Code review round

**Interviewer:** "A teammate (or an AI) wrote this 150-line PR adding retries to a payment client. Review it as you would at work."

**Good looks like:** prioritise: correctness and safety first (retrying a non-idempotent POST can double-charge → [idempotency](../HLD/concepts/idempotency-and-delivery-semantics.md)), then failure modes (no backoff or jitter → retry storm, see [retries and backoff](../HLD/concepts/retries-backoff-and-dlq.md)), then readability. Distinguish "must fix" from "nit". 🟡 Code review as an interview format is mostly described by blogs and vendors, not big-company policy pages.

### 4.4 Build a small feature / project with an AI pair (Canva, Shopify, Cursor, startups)

**Interviewer:** "Build a booking API for meeting rooms in 60 minutes. Use your own tools."

**Good looks like:** scope first ("I'll do create + list + overlap check, skip auth"), get a thin version working end to end, test the edge cases the AI misses (back-to-back bookings, time zones), and keep explaining. Canva's reported prompts are open-ended and ambiguous on purpose so that clarifying requirements matters ([ACS, 2025](#s2)).

### 4.5 Classic no-AI round, now harder or in person (Amazon, Google, most of Big Tech)

Still a DSA or LLD problem, but more often **custom** (not verbatim LeetCode) and with more follow-ups ("now make it thread-safe", "now it doesn't fit in memory"). 🟡 Pragmatic Engineer's 2025 survey reported that Big Tech interviewers use tougher questions while startups drop algorithm questions and take-homes ([Sep 2025](#s16)).

### 4.6 System design and behavioural

- **System design** stays mostly human, whiteboard-style, since it's hard to cheat when the interviewer keeps changing constraints. New: LLM-feature prompts (section 5) and, at some companies, "critique this design" (an existing design doc with flaws).
- **Behavioural / past-project deep dive:** Google's 2026 pilot reportedly adds a technical design conversation about your past work to the Googleyness & Leadership round (🟡, [May 2026](#s5)). DoorDash says interviewers may ask how you used AI to prepare ([Oct 2025](#s7)). Expect "Tell me about a time an AI tool gave you a wrong answer; how did you notice?" Have one honest story ready.

---

## 5. System design in the AI era: new question types

🟡 These prompts come from prep sites and candidate reports (2025–2026), not from company policy pages. The point: **they are built from the same blocks as the classic questions in this repo**, plus a few new parts.

> 💡 **New terms in this section.** **Token**: the unit an LLM reads and writes, roughly ¾ of an English word; LLM APIs charge per token. **Embedding**: a list of numbers (a vector) that represents the meaning of a text, so similar texts get nearby vectors. **Vector database**: a store that finds the nearest vectors fast (e.g. pgvector in Postgres, or a dedicated store). **ANN (approximate nearest neighbour)**: a search that finds *almost* the closest vectors much faster than checking all of them; HNSW is a common graph-based ANN index. **RAG (retrieval-augmented generation)**: before asking the LLM, fetch relevant documents and paste them into the prompt so the answer is grounded in your data. **Hallucination**: the model confidently inventing facts. **Guardrails**: checks on input/output (block secrets, unsafe content, off-topic). **Prompt injection**: text inside a document or user input that tries to override the model's instructions. **Evals**: automated test sets that score model answers, like regression tests for prompts. **Inference**: running a trained model to get an answer. **TTFT (time to first token)**: latency until the first word streams back.

### 5.1 "Design an LLM-powered customer-support chatbot" (RAG)

- **Ingestion pipeline:** split help-centre docs into chunks, embed them, store vectors + metadata; re-run when docs change. This is a DAG of jobs → [workflow orchestration](../HLD/concepts/workflow-orchestration-and-dags.md), [object storage](../HLD/technologies/object-storage.md) for raw docs.
- **Retrieval:** **hybrid search** = vector similarity + keyword search (exact product names, error codes) → [inverted index](../HLD/concepts/inverted-index.md), [Elasticsearch](../HLD/technologies/elasticsearch.md). Filter by tenant/permission *before* retrieval so one customer never sees another's docs.
- **Generation:** call the LLM through a gateway (5.2); **stream** tokens to the browser → [WebSockets and SSE](../HLD/technologies/websockets-and-sse.md).
- **Safety and quality:** cite sources in answers, guardrails against prompt injection, human hand-off when confidence is low, an **eval set** run on every prompt change, feedback buttons → [observability](../HLD/concepts/observability.md).
- **Deep-dive questions to expect:** "how do you stop stale answers after a doc changes?", "how do you measure that answers are correct?", "what's the cost per conversation?"

### 5.2 "Design an LLM gateway with rate limits and cost control"

The [API gateway](../HLD/interviews/api-gateway/README.md) interview with three twists:

- **Limit tokens, not just requests.** One request can be 50 tokens or 50,000. Keep two buckets per key: requests/min *and* tokens/min, plus a monthly **budget** per team → [rate limiter LLD](../LLD/interviews/rate-limiter/README.md), counters in [Redis](../HLD/technologies/redis.md), [counters at scale](../HLD/concepts/counters-at-scale.md). You only know output tokens *after* the response, so reserve an estimate up front and settle afterwards (like a card hold).
- **Caching:** exact-match cache for identical prompts; **semantic cache** (embed the prompt, return a stored answer if a past prompt is "close enough") with a strict similarity threshold, since a loose one returns wrong answers → [caching strategies](../HLD/concepts/caching-strategies.md).
- **Routing and resilience:** cheap model for simple requests, expensive one for hard ones; fail over between providers; timeouts and circuit breakers → [resilience patterns](../HLD/concepts/resilience-patterns.md). Usage events go to [Kafka](../HLD/technologies/kafka.md) for billing, off the hot path.
- **Estimate (illustrative numbers, not real prices):** 1,000 req/s × 86,400 s/day = 86.4M requests/day. × 2,000 tokens each = 172.8B tokens/day. At an *assumed* $1 per million tokens: 172,800 M-tokens × $1 = **$172,800/day**. A 20% cache hit rate saves 0.2 × 172,800 = **$34,560/day**. This is why cost is a first-class requirement → [back-of-the-envelope](../HLD/concepts/back-of-the-envelope.md).

### 5.3 "Design semantic search over 100M documents with a vector database"

- Same shape as [search autocomplete](../HLD/interviews/search-autocomplete/README.md): **offline build, online serve**. Embed documents in batch; build ANN indexes; serve from memory.
- **Size it:** 100M docs × 768 dimensions × 4 bytes/float = 307.2 GB of raw vectors, before index overhead → too big for one box, so **shard** → [sharding and replication](../HLD/concepts/sharding-and-replication.md). (768 is a common embedding size; check the model you pick.)
- **Trade-offs to say out loud:** ANN is approximate (recall vs latency knob); hybrid with keyword search for exact terms; changing the embedding model means **re-embedding everything** (plan it like a reindex/backfill); filters (tenant, date) combined with vector search are a classic hard part.

### 5.4 "Design an AI code-review bot for pull requests"

- **Event-driven pipeline:** Git host webhook → queue → workers fetch the diff + surrounding files → LLM call → post comments → [message queues](../HLD/technologies/message-queues.md), [notification-system](../HLD/interviews/notification-system/README.md) style fan-out.
- **Correctness of the plumbing:** webhooks are delivered more than once → **idempotency key** = repo + PR + commit SHA → [idempotency](../HLD/concepts/idempotency-and-delivery-semantics.md); LLM API 429s/timeouts → [retries, backoff and DLQ](../HLD/concepts/retries-backoff-and-dlq.md); per-org token budgets (5.2).
- **Product judgement:** limit comment noise (only high-confidence findings), let humans mark false positives, track precision as the key metric, never send code from private repos to a provider the customer didn't approve.

### 5.5 "Design an LLM inference service" / "schedule GPU jobs" (infra and platform roles)

Closest to the reader's day job.

- **Request path:** load balancer → queue → GPU workers. GPUs are expensive and scarce, so the core problem is **utilisation**: **batching** (run many requests through the GPU together) raises throughput but adds waiting time → [load balancer](../HLD/technologies/load-balancer.md), [back-pressure](../LLD/concepts/back-pressure.md), [resource pools and sizing](../LLD/concepts/resource-pools-and-sizing.md).
- **Memory:** a model's weights must fit in GPU memory (e.g. a model with 7B parameters × 2 bytes each = 14 GB just for weights), plus a per-request **KV cache** (the model's stored intermediate state for the conversation so far) that grows with context length. That memory limit, not CPU, caps concurrent requests.
- **Scheduling:** placing jobs on GPU nodes is a bin-packing problem like the [Kubernetes scheduler](../under-the-hood/kubernetes-scheduler.md); training jobs need all their GPUs at once ("gang scheduling"), while inference needs fast autoscaling and warm pools because loading weights takes time.
- **SLOs:** separate TTFT from total latency; alert on queue depth and GPU memory → [alerting and SLOs](../HLD/concepts/alerting-and-slos.md), [metrics & monitoring](../HLD/interviews/metrics-monitoring/README.md).

### 5.6 "Design a ChatGPT-style chat app"

[Chat system](../HLD/interviews/chat-system/README.md) + streaming: conversation history store, token streaming over SSE, per-user rate tiers (5.2), long-running "agent" tasks run as background jobs with checkpoints → [distributed job scheduler](../HLD/interviews/distributed-job-scheduler/README.md).

> 📝 **What interviewers check in all six:** do you treat the LLM as an **unreliable, slow, expensive dependency** (timeouts, retries, budgets, fallbacks, evals), or as magic? Infra people have an edge here: it's the same mindset as depending on a flaky third-party API.

---

## 6. What still matters exactly as before

Honest answer: **almost everything in this repo.** What changed is *which part of it gets graded*.

| Still matters | Why it matters even more with AI |
|---|---|
| Clarifying requirements, scoping | AI-assisted rounds are deliberately vague (Canva); the assistant can't ask the interviewer for you |
| Estimates with arithmetic | LLM features add a new cost axis (tokens, GPUs); "it costs $170k/day" ends debates |
| Trade-offs (consistency vs availability, push vs pull, cache vs freshness) | The AI proposes *a* design; you must say why it's wrong for *these* requirements |
| Concurrency, idempotency, failure handling | The bugs AI writes most confidently are races, missing idempotency and unsafe retries; you catch them in review |
| Data structures and complexity | Still asked directly in the no-AI rounds; needed to spot an O(n²) loop the AI wrote |
| OOP modelling, interfaces, patterns (LLD) | Multi-file codebase rounds are LLD in disguise: you need to see where a feature belongs |
| Communication | Every new format scores "explain what you're doing and why" |

What **did** lose value: memorising solutions to famous LeetCode problems verbatim, and reciting a canned design without adapting it to follow-ups.

---

## 7. How to use this repo now: concrete prep advice

1. **Keep doing the L4/L5/L6 files as written, but out loud.** Explaining trade-offs in your own words is the skill every round now scores. Record yourself once per week on one HLD and one LLD.
2. **Practise in both modes.** For each LLD problem (e.g. [rate limiter](../LLD/interviews/rate-limiter/README.md), [thread pool](../LLD/interviews/thread-pool/README.md)): (a) solve it with no AI, timed, in plain `javac`; (b) solve it again with an AI assistant and compare. Note every bug the AI introduced. That list is your interview gold.
3. **Critique AI output on purpose.** Ask an AI tool for a design of a problem in this repo (e.g. the payment system), then grade it against the L5/L6 files: what did it miss (idempotency? reconciliation? hot keys?). This trains exactly the "critique this design" and code-review rounds.
4. **Practise reading unfamiliar code.** Clone a mid-size open-source Java project, pick a failing issue, and time how long it takes to find the relevant class *before* using AI. Then use AI and compare. Your infra work (reading other teams' services during incidents) already builds this.
5. **Learn your tool deeply.** In allowed rounds, fumbling the tool costs time. Know how to give the assistant context, ask for small diffs, and run tests. 🟡 Candidates report free tiers hitting usage limits mid-interview ([Blind, 2026](#s7)); check before the day.
6. **Prepare the AI behavioural story.** "A time an AI tool was wrong and how you caught it", "how you review AI-generated code on your team", "how you'd decide whether to add an LLM feature".
7. **Ask the recruiter, every round:** "Is AI allowed? Which tools? Is it my machine or your environment?" Then follow the rule exactly. Using AI where it's banned is disqualifying at Amazon and Anthropic, and reported as cheating elsewhere.
8. **Add one AI-era HLD to your rotation** (section 5) using the building blocks you already know from this repo.

---

## 8. Gaps in this repo worth filling later

Not written yet; a list for the roadmap owner to consider.

1. **RAG and vector databases** (concept + technology file): chunking, embeddings, ANN/HNSW, hybrid search, re-embedding.
2. **LLM gateway** (HLD interview): token-based rate limiting, budgets, semantic cache, provider failover, metering.
3. **LLM inference serving and GPU scheduling** (HLD interview or Under the Hood): batching, KV cache, autoscaling, gang scheduling.
4. **Evals, guardrails and prompt injection** (concept): how to test and secure non-deterministic features.
5. **AI-assisted coding round practice** (LLD format): a small multi-file Java codebase with seeded bugs and failing tests, to practise sections 4.1–4.3.
6. **Code review as an interview** (guide): a checklist and 2–3 sample PRs with planted issues.

---

## 9. Sources

Dates are publication dates where visible; "via" means the original could not be opened and a report quoting it was used.

1. <a id="s1"></a>Canva Engineering Blog, Simon Newton, "Yes, You Can Use AI in Our Interviews. In Fact, We Insist" (11 Jun 2025). https://www.canva.dev/blog/engineering/yes-you-can-use-ai-in-our-interviews ✅
2. <a id="s2"></a>ACS Information Age, "Canva insists job applicants use AI coding in interviews" (2025). https://ia.acs.org.au/article/2025/canva-insists-job-applicants-use-ai-coding-in-interviews.html 🟡
3. <a id="s3"></a>Business Today, "Meta to test job applicants with AI-assisted coding interviews" (31 Jul 2025), citing 404 Media; Meta spokesperson confirmation reported by Business Insider (2025). https://www.businesstoday.in/technology/news/story/meta-to-test-job-applicants-with-ai-assisted-coding-interviews-amid-ai-expansion-plans-487200-2025-07-31 ; https://www.404media.co/meta-is-going-to-let-job-candidates-use-ai-during-coding-tests/ 🟡
4. <a id="s4"></a>interviewing.io, "How to use AI in Meta's AI-assisted coding interview" (2025–26, prep company). https://interviewing.io/blog/how-to-use-ai-in-meta-s-ai-assisted-coding-interview-with-real-prompts-and-examples 🟡
5. <a id="s5"></a>Business Insider report (early May 2026) via Yahoo Finance, "Google will let candidates use AI in interviews"; also Asia Business Daily (8 May 2026). https://finance.yahoo.com/sectors/technology/articles/asking-kid-math-test-without-151500986.html ; https://www.asiae.co.kr/en/article/science/2026050809233801400 🟡
6. <a id="s6"></a>CXO Digital Pulse, "Google Reinstates In-Person Job Interviews Amid Rising AI Cheating Concerns" (2025), citing CNBC and Pichai on the Lex Fridman Podcast (Jun 2025). https://www.cxodigitalpulse.com/google-reinstates-in-person-job-interviews-amid-rising-ai-cheating-concerns/ 🟡
7. <a id="s7"></a>DoorDash Careers Blog, "Our stance on AI and interviewing" (Oct 2025) and Ivan Rudovol & Alex Danilychev, "DoorDash is rebuilding its engineering interviews around AI" (19 Mar 2026); candidate thread on Blind (2026, anecdotal). https://careersatdoordash.com/blog/doordash-is-rebuilding-its-engineering-interviews-around-ai/ ✅
8. <a id="s8"></a>GeekWire, "Is it cheating? AI use during job interviews sparks debate" (2025), with Amazon spokesperson statement; ITPro, "Amazon bans AI tools during job interviews" (2025), both citing Business Insider (Feb 2025). https://www.geekwire.com/2025/is-it-cheating-ai-use-during-job-interviews-sparks-debate-over-whether-to-restrict-emerging-tools/ 🟡
9. <a id="s9"></a>TechCrunch, "Columbia student suspended over interview cheating tool raises $5.3M to 'cheat on everything'" (21 Apr 2025). https://techcrunch.com/2025/04/21/columbia-student-suspended-over-interview-cheating-tool-raises-5-3m-to-cheat-on-everything/ 🟡
10. <a id="s10"></a>NBC News, "Kicked out of Columbia, this student doesn't plan to stop trolling big tech with AI" (2025). https://www.nbcnews.com/tech/tech-news/columbia-university-student-trolls-big-tech-ai-tool-job-applications-rcna198454 🟡
11. <a id="s11"></a>Anthropic, "How to collaborate with Claude during our hiring process" (last updated 10 Jul 2025). https://www.anthropic.com/candidate-ai-guidance ✅
12. <a id="s12"></a>Fortune, on Anthropic relaxing its AI-in-applications ban (21 Jul 2025). https://fortune.com/2025/07/21/billion-dollar-giant-anthropic-ai-ban-hiring-policy-change-job-seekers-interview-process 🟡
13. <a id="s13"></a>Karat press release (BusinessWire), "Karat Launches NextGen Interviews: The First Human-Led, AI-Enabled Talent Evaluation Solution" (10 Dec 2025). https://www.businesswire.com/news/home/20251210685922/en ✅ (vendor)
14. <a id="s14"></a>Axios, "Companies embrace in-person interviews to dodge the chatbots" (12 Aug 2025), summarising the Wall Street Journal. https://www.axios.com/2025/08/12/in-person-job-interview-artificial-intelligence 🟡
15. <a id="s15"></a>interviewing.io, "How hard is it to cheat with ChatGPT in technical interviews?" (2024; date from memory, not re-verified ❓). https://interviewing.io/blog/how-hard-is-it-to-cheat-with-chatgpt-in-technical-interviews 🟡
16. <a id="s16"></a>The Pragmatic Engineer (Gergely Orosz): deep dive on in-person interviews returning (Aug 2025) and "The Pulse #146" interviewer survey (Sep 2025); partly paywalled. https://newsletter.pragmaticengineer.com/p/the-pulse-146 🟡
17. <a id="s17"></a>HR Dive, on Gartner's prediction that 1 in 4 candidate profiles could be fake by 2028 (Jul–Aug 2025). https://www.hrdive.com/news/fake-job-candidates-ai/757126/ 🟡
18. <a id="s18"></a>Hello Interview blog: "Shopify AI-enabled coding" and "LinkedIn AI-enabled coding" (2025–26, prep company). https://www.hellointerview.com/blog/shopify-ai-enabled-coding ; https://www.hellointerview.com/blog/linkedin-ai-enabled-coding 🟡
19. <a id="s19"></a>Glassdoor Atlassian interview reviews and PracHub Atlassian AI-assisted coding guide (2026, anecdotal). https://prachub.com/resources/atlassian-ai-assisted-coding-interview-guide-2026-repository-tasks-agent-use-and-values 🟡
20. <a id="s20"></a>HackerRank Help Centre, "AI-Assisted Interviews" and "AI Assistant in Interviews" (2026). https://hackerrank-knowledge-base.help.usepylon.com/articles/5821380141 ✅ (vendor)
21. <a id="s21"></a>NewsBytes, "Indian recruiters use AI proctoring to curb campus hiring cheating" (2025–26, exact date not verified). https://www.newsbytesapp.com/news/science/indian-recruiters-use-ai-proctoring-to-curb-campus-hiring-cheating/tldr 🟡
22. <a id="s22"></a>The420.in, "India tech hiring: AI-assisted interview fraud" (2025, anonymous sources). https://the420.in/india-tech-hiring-ai-assisted-interview-fraud/ 🟡
23. <a id="s23"></a>Taggd, "GCC Skills in Demand for 2026" (staffing-firm report, 2026). https://taggd.in/blogs/gcc-skills-in-demand/ 🟡
