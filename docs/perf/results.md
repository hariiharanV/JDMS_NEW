# B-13 results: §7 targets, findings and fixes

Run on 2026-10-25 on one shared development machine: 2 vCPU, 22 GB RAM, everything on it (PostgreSQL 18.6,
OpenSearch 3.9 with a 2 GB heap, SeaweedFS, Keycloak, ClamAV, Tika with Tesseract, Gotenberg, core, one worker,
Gatling, the generator). Every figure is a lower bound for that box, not a sizing figure; the scaling notes say what
the numbers mean for production hardware (O-06).

## Data

| Item | Result |
| --- | --- |
| Synthetic documents (tenant PERF) | 1,000,062: 998,062 generated in 2 h 3 min (488,000 per hour), plus test uploads; 30% Arabic; 6 types in 2 cabinets; 20 branches; 200,000 customers; 2% in the recycle bin; 15% INTERNAL, the rest CONFIDENTIAL or RESTRICTED |
| Storage | About 1.1 GB per 100,000 documents in PostgreSQL and the object store (two encrypted blobs each: original and extracted text); search index 2 GB |
| Full index rebuild of the tenant | 53 min 37 s for 1,000,062 documents (about 311 per second), CPU-bound on the 2 vCPUs; the catch-up pass after the alias switch found nothing to rewrite |

## §7 targets

| Measure | Target | Measured | Verdict |
| --- | --- | --- | --- |
| Search response p95 on 1 million documents | < 1.5 s | 705 ms at 5 searches/s, 835 ms at 7/s; saturates at 10/s (p95 14.4 s) | Met up to 7 searches/s on this box ([gatling/search.md](gatling/search.md)) |
| Metadata searchable after upload | immediately after commit | p50 0.96 s, p95 2.3 s after the upload's response (indexing runs right after commit, asynchronously; OpenSearch refreshes every second) | Near-real-time, not "when the upload returns"; see finding 6 |
| Content searchable, digital, p95 | < 2 min | 7.1 s (257 uploads) | Met |
| Content searchable, scans with OCR, p95 | < 10 min | 17.6 s (41 of 43 scans; the other 2 were processed, but OCR misread their 12-letter test word) | Met |
| First-page preview p95 | < 2 s | 583 ms during ingest (300 opens) | Met |
| Bulk ingestion | 100,000 documents/hour (worker scaled out) | 2,756 per hour with one worker on this box (80% digital PDFs, 20% scans); about 2.6 CPU-seconds per document for the whole stack | Not measurable on one box; scaling estimate below |
| Permission revocation | effective within 60 s on every path | see [gatling/revocation.md](gatling/revocation.md) | see there |
| Max file size | 500 MB configurable (PoC default 100 MB) | unchanged (B-03, B-06 limits) | n/a |

**Bulk ingestion scaling.** Processing is CPU work in ClamAV, Tika (with Tesseract for scans), Gotenberg and the
worker; the workers share nothing but the job table. At about 2.6 CPU-seconds per document for this mix, 100,000
documents per hour need about 72 vCPUs of processing capacity (workers with their Tika, ClamAV and Gotenberg
replicas), plus headroom: for example eight to ten 8-vCPU processing nodes. The core is not the limit for ingest
(uploads are streaming writes); the database load per document is small.

## Findings and what changed

1. **Index rebuild took days.** The catch-up pass wrote one document at a time with `refresh=wait_for` (about one
   second each) and every document was loaded with about ten queries. Now: pages loaded with a few queries, text read
   concurrently, several pages in flight, bulk writes with refresh off until the load ends, and a catch-up pass that
   compares versions and rewrites only what changed. 2,000 documents: from over half an hour to 14 s; one million:
   54 minutes on this box, CPU-bound (scales with cores and `jdms.search.rebuild.pages-in-flight`). A failed rebuild
   now drops its half-built index.
2. **Long jobs ran twice; dead workers' jobs waited 15 minutes.** Job claims had a fixed 15-minute lease: a rebuild
   longer than that was claimed again by the same core and run concurrently, and the jobs of a killed worker waited
   out the lease. Running jobs now renew their lease (heartbeat every third of the lease) and the lease is 2 minutes:
   the 54-minute rebuild ran once, and a killed worker's jobs are taken over within 2 minutes.
3. **The upload quota check summed every version of the cabinet on each upload.** At a million documents that is a
   parallel join over 600,000 rows per upload: seconds each, connections held, the pool exhausted, uploads failing
   with 500. Now a usage counter per cabinet (`cabinet_usage`, V13, backfilled in 3.5 s) is kept in step in the same
   transaction as each version written or discarded, and locked by the upload's check (two uploads can no longer both
   take the last of a quota, which the sum did not prevent either).
4. **Outbox deliveries that failed were never retried.** When a listener failed after its transaction committed
   (here: no database connection under overload), its publication stayed incomplete and nothing redelivered it: 24
   uploads were never scanned, and some index updates were lost. Now Spring Modulith marks publications stuck for 5
   minutes as failed and `EventRedelivery` resubmits failed ones every minute; every listener tolerates a second
   delivery (the quarantine notification checks before creating). After the restart, the 23 stranded uploads were
   processed within 20 s of the first redelivery.
5. **Overload answered 401 and 500.** With every database connection busy, requests waited 30 s and failed: as 500,
   or as 401 when the user profile could not be read during authentication. Now they wait at most 10 s and get 503
   `SERVICE_UNAVAILABLE` with `Retry-After`, from the API and from the authentication step; clients retry with the
   same `Idempotency-Key`.
6. **Metadata is searchable within about a second, not when the upload returns.** Indexing runs right after commit
   on another thread and OpenSearch makes writes visible at its next refresh (1 s). Making it "immediate" would mean
   indexing inside the upload request with `refresh=wait_for` (adds up to a second to every upload). Left for O-06:
   the target wording should say "within 2 seconds" or the upload path should wait.
7. **Index jobs gave up after about 75 s.** Jobs queued by the core had 5 attempts with a 5 s doubling backoff; an
   OpenSearch restart of two minutes left index jobs dead. Default attempts are now 10 (about 20 minutes of outage for
   core jobs, 2 hours for worker jobs).
8. **PostgreSQL's shared memory.** Docker gives containers 64 MB of `/dev/shm`; parallel queries on large tables
   failed with "could not resize shared memory segment". The compose file now gives PostgreSQL 256 MB (production
   PostgreSQL hosts are configured, not containers with defaults).
9. **Search: per-hit access re-check queries grants one document at a time** (20 queries for a page of 20). Not
   changed in B-13: the cost is small next to OpenSearch's (scoring and facets over large hit sets, about two thirds
   of the CPU at 5 searches/s). Batch it when search is tuned (with `track_total_hits` capped, which also helps the
   facets on very broad queries). The synthetic vocabulary is small, so a single word matches a quarter of the corpus;
   real corpora give narrower hit sets.

## Scaling notes

- Search capacity is OpenSearch CPU: dedicated search nodes (and replicas for read throughput) move the knee, which
  is between 7 and 10 searches per second on this shared box.
- The rebuild is CPU-bound in the core (decryption, text cleaning, JSON), the object store and OpenSearch; on separate
  hosts with more cores, raise `pages-in-flight` and `read-concurrency`.
- Ingest scales with worker nodes (and their Tika, ClamAV and Gotenberg); the job table queue with `SKIP LOCKED` is
  shared and was not a bottleneck.
