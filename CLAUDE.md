# System Design Prep — Repo Rules

This repo is a personal system-design interview prep library. Every session that adds content must follow these rules.

## Reader profile

- Java developer, ~2 years of experience, currently working on infrastructure.
- Preparing for HLD (high-level design) and LLD (low-level design) interviews.
- Explain jargon the first time it appears, or link to the reference file that explains it.
- Prefer analogies to infra work the reader already knows (load balancers, k8s, configs, metrics, on-call) when they help.
- If something teaches a useful lesson, include it — nothing is "out of scope" if it builds understanding.

## Folder layout

```
HLD/
  interviews/<problem>/        one folder per problem
    README.md                  problem, how to read the folder, level comparison table
    L4-mid.md                  answer expected from a mid-level engineer (SDE2)
    L5-senior.md               answer expected from a senior engineer
    L6-staff.md                answer expected from a staff engineer
  technologies/<tech>.md       Redis, Kafka, Postgres, ... (tools)
  concepts/<concept>.md        consistent hashing, ID generation, ... (ideas)
LLD/
  interviews/<problem>/
    README.md, L4-mid.md, L5-senior.md, L6-staff.md
    java/                      runnable code (plain javac, no build tool)
    js/                        runnable code (plain node, no npm deps)
  libraries/java/<lib>.md      JDK classes / libraries used in solutions
  libraries/js/<lib>.md
  concepts/<concept>.md        design patterns, SOLID, ...
```

- Same problem, different depth per level → one folder, one file per level.
- If a level would get a genuinely different question, create a separate problem folder.
- Reference files (technologies / concepts / libraries) are shared across interviews. Before creating one, check whether it already exists and extend it instead.

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
