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
| [rsync's rolling hash](rsync-rolling-hash.md) | How does rsync send only the bytes that changed? |
| [Content-defined chunking](content-defined-chunking.md) | How do sync and backup tools dedupe files when one inserted byte shifts everything? |
| [Adaptive bitrate in the player](adaptive-bitrate-player.md) | Why does your video drop to 360p, and how does the player decide to climb back up? |
| [How a voice is stored in a file](how-sound-is-stored.md) | What in those numbers is the pitch, and what makes a voice yours? |
| [Video compression](video-compression.md) | How is a 1080p video 5 Mbps when raw it would be 1.5 Gbps, and how does the server make the 480p copy? |
| [Upscaling & super-resolution](upscaling-and-super-resolution.md) | Can you really turn a blurry 360p video into HD? |
| [Erasure coding](erasure-coding.md) | How do storage systems survive lost disks with 1.5× overhead instead of 3 copies? |
| [Double-entry ledgers](double-entry-ledgers.md) | How do payment systems make money impossible to lose? |
| [Kafka's speed tricks](kafka-speed-tricks.md) | How does Kafka push millions of messages a second through ordinary disks? |
| [Kubernetes scheduler](kubernetes-scheduler.md) | How does Kubernetes pick a node for your pod? |
| [Containers: namespaces & cgroups](containers-namespaces-cgroups.md) | What is a container, really? |
| [JVM garbage collectors](jvm-garbage-collectors.md) | How does ZGC pause for under a millisecond? |
| [TLS 1.3 handshake](tls-1-3-handshake.md) | How is an encrypted connection set up in one round trip? |
| [Password hashing](password-hashing.md) | How do you make cracking stolen passwords deliberately slow? |
| [Shazam's audio fingerprints](shazam-audio-fingerprinting.md) | How is a song recognised in 3 seconds in a noisy café? |
| [Anycast](anycast.md) | How does one IP address (1.1.1.1, 8.8.8.8) live in hundreds of cities? |
| [Signal's double ratchet](signal-double-ratchet.md) | How does stealing today's key not reveal yesterday's messages? |
| [Route planning](route-planning.md) | How does a maps app find a route across a country in milliseconds? |
| [QR codes and Reed–Solomon](qr-codes-reed-solomon.md) | How does a QR code still scan with a corner torn off? |
| [Maglev hashing](maglev-hashing.md) | How do load balancers spread connections evenly and survive a server dying? |
| [UPI](upi.md) | What happens in the ~2 seconds between "Pay" and "₹ debited"? |

Every planned page is done; new ideas go in the [roadmap](../ROADMAP.md).

Runnable demos live in [`code/`](code/).

⬅️ [ROADMAP](../ROADMAP.md) · 🏠 [Home](../README.md)
