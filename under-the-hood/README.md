# 🔍 Under the Hood

> Curiosity-driven deep dives into **one specific clever invention** each: what was broken before it, the idea that fixed it, how it works step by step, and where you've been using it without knowing.

How this differs from the rest of the repo:

| Section | Question it answers |
|---|---|
| [Interviews](../README.md#interviews) | "Design X" |
| Concepts / technologies | "What is X, and when do I use it in a design?" |
| [Case studies](../ROADMAP.md#-case-studies-how-real-companies-actually-built-it) | "What did company Y actually build?" |
| [See it work](../ROADMAP.md#-see-it-work-small-runnable-pieces-of-hld-systems) | "Watch mechanism X run" |
| **Under the Hood** | **"How does this clever thing actually work, and why was it invented?"** |

Each page follows the same shape: the hook question → life before it → the clever idea → step by step (diagram, real numbers) → where you've used it → limits → try it → where it shows up in this repo → sources.

## Pages

| Topic | The question |
|---|---|
| [epoll](epoll.md) | How does one thread handle 100,000 connections? |
| [B-tree](b-tree.md) | How does a database find one row among a billion in ~3 disk reads? |
| [HyperLogLog](hyperloglog.md) | How do you count a billion unique users in 12 KB? |
| [Git's object store](git-object-store.md) | How does Git keep your entire history so cheaply? |
| [Postgres MVCC](postgres-mvcc.md) | How do readers and writers not block each other? |

More are planned (rsync, content-defined chunking, Kafka's speed tricks, the Kubernetes scheduler, TLS 1.3, containers, JVM garbage collectors, video compression, Shazam, route planning): see the [roadmap](../ROADMAP.md).

Runnable demos live in [`code/`](code/).

⬅️ [ROADMAP](../ROADMAP.md) · 🏠 [Home](../README.md)
