# 0021 — Performance, resilience and OCR tooling (B-13)

- Status: Accepted
- Date: 2026-10-25

## Context

B-13 asks for evidence against the §7 targets of the functional spec on one million documents, resilience tests,
an OCR benchmark (Tesseract against PaddleOCR, English and Arabic) and a backup and restore rehearsal. That needs a
data generator, a load-test tool, a benchmark harness and a few changes the measurements showed were necessary.

## Decision

**Test tooling lives apart from the product.** `backend/jdms-perf` is a test-only Maven module (never deployed):
its main code is the synthetic data generator, its tests are the Gatling simulations. `perf/` holds the OCR benchmark
(a container image of its own), the resilience rehearsal and the backup and restore scripts. Results are committed
under `docs/perf/`.

**Synthetic tenant, written directly.** The generator writes the tenant `PERF` straight into the core's tables
(PostgreSQL adapter, bound parameters) and its blobs through `jdms-platform`'s envelope encryption, exactly as the core
stores them after upload, approval and processing of a text file. Going through the API would run every document
through antivirus, extraction and indexing: days of processing on the test machine for data whose only purpose is
volume. The API path is measured separately by the ingest simulation. The generator writes no audit events (test
data loading, not user activity); the tenant holds synthetic data only and its catalog is a copy of the BANK1 local
seed. Documents are deterministic per seed and index, so runs can be resumed.

**Gatling for load tests.** k6 is AGPL-3.0 (not allowed). Gatling's engine and Java DSL are Apache-2.0; its HTML
report module (`gatling-charts-highcharts`) is under the "Gatling Highcharts" licence (free of charge, no
modification or reuse), which the product owner approved on 2026-10-25 for this test-only module. It is a test-scope
dependency, so it is never in a shipped artefact and the licence check (which excludes test scope) is unaffected.
Simulations get user tokens the way the web client does (authorization code with PKCE through the login form), so no
extra client or grant is added to the realm.

**Backup rehearsal tooling.** The object store is copied at the S3 level with rclone (MIT, a pinned container image
run by `perf/backup/backup.sh` and `restore.sh`), so the same scripts work against any S3 store; objects stay
encrypted in the backup and the keys stay in the secret store.

**OCR benchmark in a container.** PaddleOCR 3 and PaddlePaddle (Apache-2.0) run in-process in a Python image built
from `perf/ocr-benchmark/Dockerfile` (models downloaded at build time). Tesseract is measured through the JDMS Tika
server with the worker's own headers, so the numbers are those of the production path. The sample set follows the O-02
default (synthetic documents): banking pages rendered by Gotenberg, rasterised and degraded to three qualities. This
image is benchmark tooling, not product infrastructure; adopting PaddleOCR in the worker would need its own ADR.

**Changes the measurements forced** (product code):
- Index rebuild: the first version wrote the catch-up pass one document at a time with `refresh=wait_for` (about a
  second per document: days for a million) and loaded each document with about ten queries. The rebuild now loads
  pages of documents with a few queries, reads text renditions concurrently, keeps several pages in flight, writes in
  bulk (split at 8 MB) with refreshes off until the load completes, and the catch-up pass compares versions
  (`_mget`) and rewrites only documents that changed during the first pass.
- Job leases with a heartbeat: a running job's lease is renewed every third of the lease. Before, a crashed worker's
  jobs waited for a 15-minute lease, and a rebuild longer than the lease was claimed again by the same core and run
  twice at once. Leases are now 2 minutes (core and worker): a crashed worker's jobs are taken over within 2 minutes,
  and a long job keeps its claim while its runner lives.
- Job attempts: jobs queued by the core had 5 attempts, so with the core's backoff (5 s doubling) an index update gave
  up after about 75 seconds; the rehearsal's OpenSearch restart took two minutes and left index jobs dead. The default
  is now 10 attempts: core jobs ride out about 20 minutes of outage, worker jobs (30 s doubling to 30 min) about two
  hours. Dead jobs stay retryable through `/admin/jobs`.
- Cabinet quota: every upload summed the size of all versions in the cabinet (a join over 600,000 rows at a million
  documents: seconds per upload, the connection pool exhausted). A usage row per cabinet (`cabinet_usage`, V13,
  backfilled) is kept in step in the same transaction as every version written or discarded and locked by the
  upload's check, so concurrent uploads cannot both take the last of a quota.
- Outbox redelivery: a listener that failed after its transaction committed (no connection under overload) left its
  publication incomplete, and nothing redelivered it. Spring Modulith's staleness monitor now marks publications stuck
  for 5 minutes as failed and `EventRedelivery` resubmits failed publications every minute. Every listener tolerates a
  second delivery (the quarantine notification checks before creating one).
- Database unavailable or every connection busy: requests wait at most 10 s for a connection, then get 503
  `SERVICE_UNAVAILABLE` with `Retry-After`, from the API and from authentication (which reads the user profile),
  instead of 500 or 401 after 30 s.

## Consequences

- Measurements on the shared two-CPU development machine are lower bounds; `docs/perf/` states the environment of
  each run. Production sizing needs a run on representative hardware (O-06).
- The generated tenant is not created through maker-checker or audited; it must never exist in a bank's environment.
  `jdms-perf` refuses nothing by itself: it is run by hand against test stacks only.
