# HLD Interview: Design a Video Streaming Platform (YouTube / Netflix)

> "Design a platform where people upload videos and millions watch them smoothly on any device and any connection."

This interview teaches how to handle **huge files and huge bandwidth**: uploads that survive bad networks, a processing factory that turns one file into a ladder of qualities, storage that grows by a petabyte a day, and delivery measured in terabits per second, where the CDN, not your servers, does almost all the work.

## How to read this folder

> 👉 **Start with [00-understand-the-product.md](00-understand-the-product.md).** It explains renditions, segments, manifests and adaptive streaming, with "Stats for nerds" and DevTools experiments you can do right now.
>
> 📚 **Then the case studies [Video platforms: upload, transcode, store, stream](../../../case-studies/video-upload-transcode-and-storage.md)** (what YouTube, Netflix and Meta actually built) and **[Netflix: Open Connect and chaos engineering](../../../case-studies/netflix-open-connect-and-chaos-engineering.md)** (caches inside ISPs, steering, breaking production on purpose).

| File | Who it's for | What "good" looks like |
|---|---|---|
| [00-understand-the-product.md](00-understand-the-product.md) | Everyone, first | Know why uploads, transcoding, segments and CDNs exist |
| [L4-mid.md](L4-mid.md) | Mid-level / SDE2 | Estimates (≈8 Tbps delivery, ≈1 PB/day storage), direct multipart uploads, queue + transcoding workers, bitrate ladder, HLS segments + manifests, CDN, video state machine |
| [L5-senior.md](L5-senior.md) | Senior | Chunked parallel transcoding as a DAG, publish-fast, storage tiering, origin shield + coalescing, codec economics, sprites and captions, view counts and QoE events |
| [L6-staff.md](L6-staff.md) | Staff | QoE vs cost, live streaming at cricket scale, caches inside ISPs, cost model, DRM, multi-region, build vs buy |

**Suggested order:** product page → L4 → L5 → L6 → case study.

## The same problem at three levels: at a glance

| Topic | L4 (mid) | L5 (senior) | L6 (staff) |
|---|---|---|---|
| Upload | Pre-signed multipart, direct to storage | Dedup by content hash | Uploads to nearest region; replicated originals |
| Processing | Queue + whole-video workers, fixed ladder | Keyframe-aligned chunks in parallel, DAG, priority lanes, publish low renditions first | Live: real-time encode with hot standby |
| Formats | H.264 + HLS segments | Better codecs only for popular videos (arithmetic) | AV1 rollout strategy; DRM packaging |
| Storage | Originals + renditions in object storage | Tiering by popularity; re-encode on demand | Cost model: delivery ≫ storage ≫ encoding |
| Delivery | CDN, immutable cached segments | Origin shield, request coalescing, hit-ratio math, signed URLs | Own caches inside ISPs (push vs pull); per-ISP QoE |
| Player | ABR: bandwidth + buffer | Seek behaviour, previews, captions | QoE metrics as the product's health |

## Building blocks used

**Concepts (new for this problem):** [Adaptive bitrate streaming](../../concepts/adaptive-bitrate-streaming.md) · [Video transcoding pipeline](../../concepts/video-transcoding-pipeline.md) · [Resumable & chunked uploads](../../concepts/resumable-and-chunked-uploads.md)

**Concepts (reused):** [Back-of-the-envelope](../../concepts/back-of-the-envelope.md) · [Caching strategies](../../concepts/caching-strategies.md) · [Idempotency](../../concepts/idempotency-and-delivery-semantics.md) · [Retries, backoff & DLQ](../../concepts/retries-backoff-and-dlq.md) · [Counters at scale](../../concepts/counters-at-scale.md) · [Content fingerprinting & dedup](../../concepts/content-fingerprinting-and-dedup.md) · [Observability](../../concepts/observability.md) · [Resilience patterns](../../concepts/resilience-patterns.md)

**Technologies:** [CDN](../../technologies/cdn.md) · [Object storage (S3)](../../technologies/object-storage.md) · [Message queues](../../technologies/message-queues.md) · [Kafka](../../technologies/kafka.md) · [PostgreSQL](../../technologies/postgresql.md)

**Under the Hood:** [Why your video drops to 360p, and how the player climbs back](../../../under-the-hood/adaptive-bitrate-player.md) (with a runnable simulator comparing player strategies)

**Related LLD:** [Vending Machine](../../../LLD/interviews/vending-machine/README.md): the same "explicit states and transitions" thinking as a video's UPLOADING → PROCESSING → READY lifecycle, at the scale of one machine.

## The core insight

1. **Separate the write path from the read path.** Uploading and processing are slow, bursty and asynchronous; watching must be instant and never depend on them.
2. **Turn video into static files.** Once a video is segments + manifests, the player does the thinking and a CDN can serve it at any scale.
3. **Delivery is the bill.** Most decisions (codecs, ladders, cache hit ratio, ISP caches) are ways to deliver fewer bytes from fewer places.
