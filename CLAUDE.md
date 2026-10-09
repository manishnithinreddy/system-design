# System Design Prep — Repo Rules

This repo is a personal system-design interview prep library. Every session that adds content must follow these rules.

## Reader profile

- Java developer, ~2 years of experience, currently working on infrastructure.
- Preparing for HLD (high-level design) and LLD (low-level design) interviews.
- Explain jargon the first time it appears, or link to the reference file that explains it.
- **Every technical term gets a short plain-words explanation at first use in each file** (1–2 sentences, inline or as a 💡 note), even when it's linked, and even for "background" terms from hardware, OS or networking (e.g. L1/L2 cache, ns/µs, buffer pool, syscall, TCP handshake). Keep it brief: enough to follow the text. The reader researches deeper on their own.
- Prefer analogies to infra work the reader already knows (load balancers, k8s, configs, metrics, on-call) when they help.
- If something teaches a useful lesson, include it — nothing is "out of scope" if it builds understanding.

## What to build next

The reader asked Claude to choose the problems. **[ROADMAP.md](ROADMAP.md) is the plan of record**: continue with the first unfinished row (HLD + LLD pair), then move it to "Done" and update the indexes. Only deviate if the reader asks.

## Side tracks

- **Case studies** live in `case-studies/<name>.md`: how a real company built a system, from public sources. **Cite sources inline with years; mark anything unverified as such.** Explanations and diagrams only, no code. Link to the related interview and concept files.
- **See it work** pieces live in `see-it-work/<name>/` (README.md + small Java or Node code). Understanding first: the README explains the mechanism step by step, shows sample output, and lists "things to try" (kill a node, partition the network). Keep code small and readable (≈50–150 lines); no external dependencies.

- **Under the Hood** pages live in `under-the-hood/<topic>.md`: curiosity-driven deep dives into one specific invention ("How does one thread handle 100k connections?"). Different from concepts (which answer "what is it and when do I use it in a design"). Sections: 1) the hook question, 2) life before it (what was broken, who invented the fix, when), 3) the clever idea in 1–2 sentences, 4) step by step with a Mermaid diagram and real numbers, 5) where the reader has used it without knowing, 6) limits and trade-offs, 7) try it (a command, or a tiny runnable demo only if it really helps; run it and quote real output), 8) where it shows up (links to interviews/concepts), 9) sources with years. ~150–250 lines. Cadence: 1–2 per roadmap pair, chosen to match it.

## Folder layout

```
HLD/
  interviews/<problem>/        one folder per problem
    00-understand-the-product.md  the product from a USER's point of view (read first)
    README.md                  problem, how to read the folder, level comparison table
    L4-mid.md                  answer expected from a mid-level engineer (SDE2)
    L5-senior.md               answer expected from a senior engineer
    L6-staff.md                answer expected from a staff engineer
  technologies/<tech>.md       Redis, Kafka, Postgres, ... (tools)
  concepts/<concept>.md        consistent hashing, ID generation, ... (ideas)
LLD/
  interviews/<problem>/
    00-understand-the-product.md, README.md, L4-mid.md, L5-senior.md, L6-staff.md
    java/                      runnable code (plain javac, no build tool)
    js/                        runnable code (plain node, no npm deps)
  libraries/java/<lib>.md      JDK classes / libraries used in solutions
  libraries/js/<lib>.md
  concepts/<concept>.md        design patterns, SOLID, ...
```

- Same problem, different depth per level → one folder, one file per level.
- If a level would get a genuinely different question, create a separate problem folder.
- Reference files (technologies / concepts / libraries) are shared across interviews. Before creating one, check whether it already exists and extend it instead.

## Product intro file (00-understand-the-product.md), required for every problem

Never assume the reader has used the product or knows why its features exist. Before any design, explain it as a user would experience it:

1. The problem as a short story (who needs it, what goes wrong without it)
2. Where the reader has already seen it in real life / at work
3. Each feature mentioned in the interviews, through a situation where someone needs it, and which interview question it leads to
4. The key mechanism in plain words (e.g. what an HTTP redirect is), with a diagram
5. "Try it yourself": point to a real public product/API the reader can use or `curl`. **Do not build toy/playground implementations** (deferred, see below)
6. Experience → requirements table (functional / non-functional)
7. Mini glossary

## Interview file format (L4/L5/L6)

Written as a dialogue between **Interviewer** and **Candidate**, with short "📝 Note" asides that explain why an answer is good or what the interviewer is checking. Sections:

1. Clarifying requirements (functional + non-functional)
2. Back-of-the-envelope estimates (HLD) / core entities (LLD)
3. API / interfaces
4. High-level design with a Mermaid diagram (HLD) / class diagram (LLD)
5. Deep dives (the level determines how many and how deep)
6. Interviewer follow-ups / curveballs
7. "What the interviewer was evaluating" — a checklist for that level
8. Common mistakes at this level

Link every technology/concept the first time it is used, e.g. `[Redis](../../technologies/redis.md)`.

## Reference file format (technology / concept / library)

1. **One-line summary** — what it is in plain words
2. **The problem it solves** — start from the pain, then the fix
3. **How it works** — just enough internals, with a Mermaid diagram when useful
4. **When to use it**
5. **When NOT to use it** — and why using it there is a mistake
6. **Commonly confused with** — side-by-side comparison table
7. **Common mistakes / misuse** in interviews and production
8. **Interview cheat-sheet** — 3–6 sentences to say out loud
9. **Used in** — links back to interviews that use it

## Style

- Markdown, Mermaid for diagrams (renders on GitHub).
- Clear over clever. Short paragraphs, tables for comparisons.
- Numbers in estimates must be shown with the arithmetic.
- Code must compile/run. Java 21 (`javac` + `java`), Node 22 (`node`). No external dependencies in solution code; mention production libraries in the libraries/ docs instead.

## Deferred ideas (come back after the main content is complete; do not build yet)

- ~~Toy playground per problem~~: merged into the **"See it work"** side track in ROADMAP.md (small runnable pieces of HLD mechanisms, explanation first).
- ~~**Jargon sweep of older files**~~: done (2026-10-09) for the 60 files written before the rule. New files must follow the rule from the start.
