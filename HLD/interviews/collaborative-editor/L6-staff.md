# Collaborative Editor (Google Docs) — L6 (Staff) Interview

> **Level expectation:** the L5 mechanics (OT vs CRDT, failover with fencing, hot docs, compaction) are known. The staff conversation is about **choosing the collaboration model as a long-term bet**, **multi-region** with users editing the same doc from different continents, **correctness verification** for algorithms that fail silently, **security and enterprise** requirements, **cost of history**, platform reuse across products, and build vs buy. Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. The model is a ten-year bet

**🧑‍💼 Interviewer:** We're starting a new editor product. OT or CRDT?

**🧑‍💻 Candidate:** The choice locks in data formats, client libraries, offline behaviour and storage costs for years, so decide on **product requirements**, not fashion:

| If the product needs… | Lean towards |
|---|---|
| Always-online editing, very large documents, strict server control | Server-ordered OT (smallest metadata, simplest server-side reasoning) |
| Offline-first, multiple devices, peer-to-peer or edge sync | CRDT |
| Non-text structures (canvas objects, spreadsheets, whiteboards) | Per-property last-writer-wins registers on objects (simple CRDT-like) plus a server authority; Figma publicly described this style for its multiplayer design |
| Small team, fast delivery | An existing library (Yjs, Automerge) or hosted service rather than a home-grown algorithm |

Either way, keep **a server as the authority** for permissions, durable history, search and audit, even if the merge logic itself doesn't need one.

> 📝 **Note:** Showing that you separate "merge algorithm" from "who is authoritative" is the staff insight. Many teams conflate them.

---

## 2. Multi-region: Mumbai and New York in the same doc

**🧑‍💻 Candidate:** With server-ordered OT, every op goes to the document's single owner.
- **Home the document in one region** (where its owner/most editors are). Editors far away pay the round trip for *others to see* their edits (~200 ms Mumbai ↔ New York), but their own typing is still local and instant.
- **Move the home** when the editing population shifts (e.g. the doc's owner relocates): drain, snapshot, transfer the lease to a session server in the other region, and redirect clients.
- **Read-only viewers** can be served from a regional broadcast tier fed by the home region.

With a CRDT, each region can accept ops and **exchange them asynchronously**: true multi-region writes, at the cost of more metadata and eventual (not immediate) consistency between regions ([CAP & consistency](../../concepts/cap-and-consistency.md)). For a doc editor, a few hundred milliseconds of cross-region delay is fine either way; the decision is driven by offline and resilience needs, not latency.

---

## 3. Verifying correctness of algorithms that fail silently

**🧑‍💼 Interviewer:** How do you sleep at night knowing a transform bug could silently corrupt documents?

**🧑‍💻 Candidate:** Layers:
1. **Property-based / randomized tests:** generate random concurrent op sequences for 2–5 simulated clients with random network delays and reorderings; assert all replicas converge and match a reference. Run millions of cases in CI; keep failing seeds as regression tests.
2. **Model checking** of the transform functions for small state spaces (all pairs and triples of ops on short strings).
3. **Production checksums:** document hash in acks (L5 §5); divergence events are alerts, with the op history attached.
4. **Shadow mode** for algorithm changes: run the new transform alongside the old on real traffic and compare outputs before switching.
5. **Recovery path:** any detected divergence makes the client reload from the server snapshot; the server is the source of truth.

This mirrors how databases are tested with deterministic simulation and fault injection ([Distributed KV Store L6 §5](../distributed-kv-store/L6-staff.md#5-how-do-you-know-its-correct)).

---

## 4. Security and enterprise requirements

- **Authorization on every op**, not just on open (L4 §5.6), with immediate revocation of live sessions.
- **Audit log** of who viewed, edited, shared, exported: separate from the op log, retained per compliance rules.
- **Data residency:** some customers require documents to stay in a region; the home-region model (§2) makes this enforceable.
- **Data loss prevention:** scanning content for secrets or personal data on share/export, done asynchronously from the op stream.
- **End-to-end encryption** is possible with CRDTs (the server relays encrypted ops it can't read) but loses server-side search, previews and some history features: the same trade-offs as [end-to-end encryption](../../concepts/end-to-end-encryption.md) in messaging.
- **Abuse via link sharing:** public docs used for spam/phishing need the same trust & safety tooling as any user-generated content.

---

## 5. Cost of history

```text
Raw ops: ~8.6 TB/day (L4). Keeping 30 days of fine-grained history:
8.6 TB × 30 ≈ 258 TB of op log
Snapshots: 1B docs × 50 KB = 50 TB current; one compressed daily snapshot for docs edited that day
(assume 50M docs/day × 50 KB ÷ 4 compression ≈ 625 GB/day)
```

Levers: shorter fine-grained window, compression (ops are very repetitive), dropping presence/cursor data entirely (never stored), deduplicating unchanged snapshots, and tiering old snapshots to archive storage ([object storage](../../technologies/object-storage.md)). Named versions and legal holds are kept regardless.

---

## 6. One collaboration platform, many products

Docs, Sheets, Slides, a whiteboard, code review comments: each needs presence, sessions, op logs, snapshots, permissions and history. Build these as a **shared collaboration platform** (session routing, op log service, presence service, snapshot service, permission checks) with **pluggable data models** (text OT, spreadsheet cells, canvas objects). Product teams own their data model and merge rules; the platform owns scale and reliability.

💡 **Infra analogy:** like Kubernetes owning scheduling, networking and health while each team brings its own container.

---

## 7. Build vs buy

| Option | When |
|---|---|
| **Open-source CRDT libraries** (Yjs, Automerge) + your own server | You need control and offline support; team can run sync servers |
| **Hosted collaboration services** (various vendors provide presence + CRDT sync as a service) | Collaboration is a feature, not the product; speed matters |
| **Embed an existing editor** with collaboration built in | Standard rich text is enough |
| **Build OT/CRDT from scratch** | Collaboration is the core product, and you can afford years of correctness work |

**🧑‍💻 Candidate:** Writing your own transform functions is one of the most underestimated projects in software; most companies should reuse a proven library and spend effort on the platform around it.

---

## 8. Curveballs

**🧑‍💼 Interviewer:** A customer pastes a 40 MB log file into a doc. Everything slows down for everyone in it.

**🧑‍💻 Candidate:** Size limits per paste and per document, chunked loading (only load and render the visible part), server-side storage of large blocks as separate objects referenced from the document, and per-doc resource limits on the session server so one huge doc can't starve the others it shares a process with.

**🧑‍💼 Interviewer:** Legal asks for "who changed this sentence, and when?" for a doc from three years ago.

**🧑‍💻 Candidate:** Fine-grained ops are long gone (§5), but daily snapshots plus the audit log answer "which day and which users edited". Exact keystroke attribution beyond the fine-grained window is a product decision: if it's a requirement for some customers, offer extended history retention as a paid/compliance feature.

---

## 9. What the interviewer was evaluating (L6)

- [ ] OT vs CRDT as a product-driven long-term decision; merge algorithm separated from authority
- [ ] Multi-region with document homing, migration, and the CRDT alternative
- [ ] Correctness verification: randomized tests, model checking, checksums, shadow mode
- [ ] Security and enterprise: per-op auth, audit, residency, DLP, E2EE trade-offs
- [ ] History cost arithmetic and retention levers
- [ ] Platform thinking across products; build vs buy

## 10. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| Picking CRDT/OT because it's trendy | Locks in costs and formats that don't fit the product |
| Treating convergence bugs as rare edge cases | Silent corruption erodes trust; needs systematic testing |
| Global single-region design for a global product | Residency and resilience requirements fail |
| Keeping every keystroke forever | Storage grows without bound for little value |
| Each product building its own collaboration stack | Duplicate effort, inconsistent reliability |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
