# Chat System — L6 (Staff) Interview

> **Level expectation:** the L5 architecture is assumed and summarised in minutes. The interview is about **consequences and operations**: what end-to-end encryption does to every other part of the design, operating a fleet of stateful connection servers, multi-region, huge groups/channels, fighting abuse without reading messages, retention and cost, and what to build vs reuse. Read [L5-senior.md](L5-senior.md) first.

Legend: **🧑‍💼 Interviewer** · **🧑‍💻 Candidate** · **📝 Note** = commentary for you.

---

## 1. Shape the problem

**🧑‍💼 Interviewer:** Design WhatsApp.

**🧑‍💻 Candidate:** Two questions change the architecture more than anything else:

| Question | If yes | If no |
|---|---|---|
| **End-to-end encrypted?** | Server stores/forwards ciphertext; no server-side search, previews, spam filtering by content or server-side history for new devices | Server can index, moderate, sync full history to any device |
| **Server is the long-term archive?** (Slack/Teams model) | Petabytes of history, retention policies, eDiscovery (legal search) | WhatsApp model: server holds messages **until delivered**, history lives on devices (and in user-controlled backups) |

**🧑‍💼 Interviewer:** Consumer app, E2EE, WhatsApp model.

**🧑‍💻 Candidate:** Then I'll design around that, and point out where E2EE changes the L5 design.

> 📝 **Note:** Asking "is it E2EE?" first is a staff move. It flips storage, search, abuse, multi-device and backup decisions all at once.

---

## 2. What E2EE changes

See [end-to-end encryption](../../concepts/end-to-end-encryption.md) for the mechanism. Consequences for the system:

| Area | L5 design | With E2EE |
|---|---|---|
| Message body | Stored as text | **Ciphertext** (scrambled bytes). Server can't read, index or preview it |
| Storage | Keep history | **Delete after delivery to all recipient devices** (+ ~30 days for offline devices). Storage drops from petabytes of history to a rolling buffer |
| Push notifications | "Ananya: Reached home" | Push says "new message"; the app wakes up, fetches and decrypts locally to show a preview |
| Groups | Server fans out one message | Sender encrypts for the group with a **sender key** shared with members; membership changes require re-keying. A big part of why groups have caps |
| Multi-device | Server syncs history to a new laptop | Each device has its own keys; sender encrypts per device. A new device can't read old messages unless another of the user's devices transfers them |
| Key server | — | New service: stores users' **public** keys (never private), serves them to senders. Its integrity is security-critical (key-change warnings, safety numbers) |
| Search | Server-side search | **On-device search only** |
| Abuse/spam | Content classifiers | **Metadata and behaviour only** (see §6) + user reports, where the reporter's device shares the reported messages |

**🧑‍💻 Candidate:** The big cost win: since the server isn't the archive, storage is a **rolling buffer**. Of ~20B messages/day, most are delivered within seconds, so the live working set is a few TB instead of PBs growing forever. Media is similar: encrypted blobs with a retention window.

---

## 3. Operating a stateful connection fleet

**🧑‍💻 Candidate:** ~150M concurrent connections on ~1,500 gateways is the operationally hardest part, and it's very infra-shaped.

### Deploys without dropping 150M users at once
- **Drain, don't kill:** on shutdown, a gateway stops accepting new connections, then tells its clients "reconnect elsewhere" **gradually** (e.g. over 10 min), each with jitter. Kubernetes analogy: a long `terminationGracePeriodSeconds` + a `preStop` hook that drains, and readiness flipped to false first.
- **Roll slowly:** 1,500 nodes × 10 min drain, a few % at a time. A full gateway rollout takes hours, which is why gateways must be **thin and rarely changed**, with logic kept in stateless services behind them.
- **Version skew:** old and new gateway/client protocol versions coexist for weeks (users don't update apps). The protocol must be versioned and backward compatible.

### Capacity and limits
- Per connection: socket buffers + TLS state + session objects ≈ tens of KB → 100k connections ≈ several GB of RAM per node. File descriptor limits (`ulimit -n`), ephemeral ports at the load balancer and conntrack tables all become real limits. Load-test the *connection count*, not just the request rate.
- **Load balancer idle timeouts** must exceed the heartbeat interval, or the LB silently kills idle connections ([load balancer](../../technologies/load-balancer.md)).

### Backpressure
- A slow client (bad network) can't read fast enough → its outbound buffer on the gateway grows. Cap per-connection buffers; if exceeded, drop the real-time push (the client will sync) or disconnect. One slow phone must never cause a gateway out-of-memory crash that disconnects 100k others.

> 📝 **Note:** This section is where an infra background is a real advantage: draining, grace periods, FD limits, LB timeouts and backpressure are things you've likely seen in production. Use that experience in the interview.

---

## 4. Multi-region

**🧑‍💻 Candidate:** Users are global; a Mumbai user shouldn't connect to Virginia (~200 ms round trip each way).

- **Home region per user**, chosen by where they usually are. Their connection terminates at the nearest gateway, which forwards to the home region's services if needed.
- **Conversation home:** the sequencer for a conversation lives in **one** region (ordering needs one owner). For a 1:1 chat between Mumbai and London, messages from the non-home side cross regions once (~100 ms extra). Accept that for ordering correctness, or home the conversation where most of its traffic is.
- **Region failure:** conversations homed there fail over to a secondary region. Sequencer state is restored from replicated storage (max seq per conversation); clients reconnect and sync. During failover, sends to those conversations briefly fail and are retried by clients with their `clientMsgId`, so nothing is duplicated.
- **Data residency:** some countries require data about their users to stay in-country. With E2EE and delete-after-delivery, the server holds little content, but **metadata** (who talks to whom) is still personal data.

---

## 5. Huge groups and channels

**🧑‍💻 Candidate:** Groups capped at ~1,000 use fan-out on write (L5). Channels/communities with millions of followers need a different product shape and path ([fan-out](../../concepts/fan-out.md)):
- **Fan-out on read:** store the post once; followers fetch the channel's recent posts when they open it (cacheable at the [CDN](../../technologies/cdn.md)/edge since every follower sees the same content).
- **Notify, don't deliver:** send a lightweight "new posts" push to followers who opted in, rate-limited per channel.
- Often **not E2EE** (WhatsApp Channels and Telegram channels are effectively public broadcasts), so caching and moderation are possible. Say this explicitly; it's a product decision with security implications.
- Reactions/view counts aggregated (counters), never per-reader rows fanned out to the author.

---

## 6. Abuse and spam without reading messages

**🧑‍💻 Candidate:** With E2EE, the server can't scan content. What it *can* use:
- **Metadata/behaviour:** a brand-new account messaging 500 strangers in an hour; high block/report rate; many recipients not having the sender in contacts; group-add spam.
- **Rate limits** on new conversations per account per day, group invites, forwards ("forwarded many times" limits were introduced to slow misinformation).
- **User reports:** the reporter's device sends the last N messages of the reported chat (they're decryptable on that device), so reviewers see reported content only.
- **Registration friction:** phone verification, device attestation; and protect the OTP flow from [SMS pumping](../notification-system/L6-staff.md#4-fraud-sms-pumping).

---

## 7. SLOs and observability

- **Delivery latency SLO** (both online): p99 < 300 ms, measured from **client timestamps** of send and receive of *synthetic* messages between probe devices in each region. Server-side metrics miss gateway and network time.
- **Sync SLO:** p99 time from reconnect to "inbox up to date".
- Per-component: gateway connection count and churn, pub/sub delivery misses (detected via client gap-fills), sequencer partition lag, inbox write latency.
- **Client telemetry** is essential: most chat incidents are only visible from the device side (a bad app release that reconnects in a tight loop is indistinguishable from a DDoS otherwise).

---

## 8. Build vs buy

| Option | Fit |
|---|---|
| Build (this design) | Hundreds of millions of users, E2EE, the product *is* messaging |
| Open protocols / servers (XMPP/ejabberd, Matrix/Synapse) | Mid-scale products; federation; avoid reinventing presence/receipts |
| Managed chat APIs (e.g. Stream, Sendbird, Twilio Conversations) | In-app chat as a *feature* of another product (marketplace buyer-seller chat): weeks, not years |

**🧑‍💻 Candidate:** If the interviewer's company is, say, a ride-hailing app wanting driver-rider chat, I'd strongly push a managed service. The L5/L6 design is a multi-year, multi-team investment that only makes sense when chat is the core product.

---

## 9. Curveballs

**🧑‍💼 Interviewer:** A new app release makes clients reconnect every 2 seconds. Connection rate goes 50×.

**🧑‍💻 Candidate:** Protect the backend first: gateways enforce per-node new-connection rate limits and return "retry after X" with jitter; auth and registry are shielded. Then stop the cause: server-driven **kill switch / config** the client respects (e.g. minimum reconnect interval pushed in the handshake), and halt the staged rollout in the app stores. Lesson: clients are part of the distributed system, so ship server-controllable knobs for reconnect behaviour *before* you need them.

**🧑‍💼 Interviewer:** Users complain messages arrive out of order in one region only.

**🧑‍💻 Candidate:** Order is fixed at the sequencer, so the *display* order on clients shouldn't vary unless (a) a client version sorts by timestamp instead of seq; (b) cross-region conversations are being sequenced in two regions after a failover misconfiguration (two owners!); or (c) gap detection is broken and late messages are appended at the end instead of inserted by seq. I'd check whether one conversation has duplicate seq numbers, which would point straight at (b).

---

## 10. What the interviewer was evaluating (L6)

- [ ] Asked about E2EE and archive model first, and traced the consequences through storage, push, groups, multi-device, search and abuse
- [ ] Gateway fleet operations: draining, slow rollouts, version skew, FD/LB limits, backpressure
- [ ] Multi-region with one sequencer owner per conversation, failover and residency
- [ ] Separate product/path for channels; fan-out on read; explicit E2EE decision
- [ ] Abuse detection from metadata, rate limits and user reports
- [ ] SLOs measured from clients; client telemetry
- [ ] Build vs buy based on whether chat is the core product
- [ ] Server-controlled client behaviour as an incident tool

## 11. Common mistakes at this level

| Mistake | Why it hurts at L6 |
|---|---|
| Treating E2EE as a checkbox | It changes storage, push, search, groups, multi-device and moderation |
| Deploying gateways like stateless services | Kills millions of connections at once; reconnect storm |
| Two sequencer owners after a regional failover | Duplicate seq numbers → ordering bugs that are hard to repair |
| Same design for 1,000-member groups and million-follower channels | Fan-out on write explodes |
| "We'll scan messages for spam" in an E2EE system | Contradicts the security model |
| Recommending a multi-year build for a chat *feature* | Ignores cost and team capacity |

⬅️ Previous: [L5-senior.md](L5-senior.md) · 🏠 [Problem overview](README.md)
