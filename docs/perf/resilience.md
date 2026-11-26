# Resilience (B-13)

Done criterion: kill the worker mid-job, restart OpenSearch, object store unavailable: no data loss, correct statuses.
Proven twice: by integration tests that run in every build, and by a rehearsal that breaks the real local stack
(`perf/resilience/rehearsal.py`, 2026-10-25, the 2-vCPU machine of [results.md](results.md), with the 1-million
document tenant in place).

## Automated tests (every build)

| Test | What it proves |
| --- | --- |
| `WorkerResilienceIT.aStepHeldByADeadWorkerIsFinishedByAnotherOnceItsLeaseExpires` | A step claimed by a worker that dies is taken over after its lease; the version ends with one result per step and one rendition per kind |
| `WorkerResilienceIT.processingWaitsOutAnObjectStoreOutage` | With SeaweedFS paused, the scan is retried with backoff and completes after the outage; nothing reported twice |
| `JobQueueIT.aLongJobKeepsItsLeaseWhileItsRunnerIsAlive` | A job running three leases long keeps its claim (heartbeat): nobody else can take it, it completes once |
| `JobQueueIT.anExpiredLeaseMakesTheJobClaimableAgain`, `aHangingServiceIsRetriedUntilTheJobIsDead` (B-06) | Lease expiry and bounded retries against a hanging Tika |
| `IntegrationApiIT` (B-09) | Upload while S3 is unavailable: 503 `STORAGE_UNAVAILABLE`, the retry with the same key creates the document once; search 503 `SEARCH_UNAVAILABLE` while OpenSearch is paused and the document indexed once it is back |
| `EventRedeliveryIT` | An outbox delivery whose listener failed after commit is delivered again (the SCAN job comes back) |
| `ProblemSecurityHandlersTest`, `ProblemDetailsAdviceTest` | No database connection: 503 with `Retry-After`, from the API and from authentication, instead of 500 or 401 |

## Rehearsal against the running stack

Each scenario uploads through the API as `perfmaker1`, breaks something for real, then checks: every upload exists
exactly once (counted in the database by its unique CIF), every version ends `DONE`, no job is `DEAD` or `FAILED`,
every document is found by its metadata and by a word that is only in its content.

### Worker killed mid-job (SIGKILL)

| Check | Result |
| --- | --- |
| Uploads | 30 (15 digital 40-page PDFs, 15 image-only scans that need OCR), uploaded while the worker was busy |
| Kill | SIGKILL 1.3 s into the batch, with 2 worker steps running; a new worker started 5 s later |
| Interrupted steps | taken over by the new worker once their lease expired (the killed worker's SCAN jobs show attempt 2) |
| All 30 processed | 124.5 s after the kill: the 2-minute lease of the interrupted steps, then their processing |
| Exactly once, searchable, no dead or failed jobs | yes, yes, yes |

Uploads never depend on the worker: the core accepted every upload while the worker was down. Before the heartbeat
(finding 2 in [results.md](results.md)) the lease was 15 minutes, so the same crash would have delayed the
interrupted documents by up to a quarter of an hour.

### OpenSearch restarted (`docker restart`) during uploads

| Check | Result |
| --- | --- |
| Uploads during the restart | 20 of 20 answered 201 (uploads do not need search) |
| Searches during the restart | 200 before and after, 503 `SEARCH_UNAVAILABLE` while down |
| OpenSearch healthy again | 55.5 s after the restart began |
| Index updates that failed meanwhile | queued as INDEX jobs and retried (up to 4 attempts); none dead |
| All 20 documents searchable by metadata and content | 24 s after the checks began |
| Exactly once, processed, no dead or failed jobs | yes, yes, yes |

A first run, before the attempts change of finding 7 in [results.md](results.md), left 8 INDEX jobs dead after 5
attempts (75 s of backoff against a two-minute restart). With 10 attempts, none died.

### Object store unavailable (`docker stop` SeaweedFS for 30 s)

| Check | Result |
| --- | --- |
| 5 uploads just before the outage | stored; their processing hit the outage and retried (scan and extract up to 3 attempts) |
| 10 uploads during the outage | first answer 503 `STORAGE_UNAVAILABLE`; clients retried every 5 s with the same `Idempotency-Key` until 201 |
| Outage | 30.6 s |
| All 15 exactly once, processed, searchable | yes; processed 31 s after the checks began, searchable 3 s later |
| Dead or failed jobs | none |

### Overload (found during the load tests)

Not a planned scenario: an upload burst far above what one worker processes exhausted the core's database
connections. It exposed two defects, both fixed (findings 4 and 5 in [results.md](results.md)): failed outbox
deliveries were never retried (24 uploads would never have been processed), and overload surfaced as 401 and 500.
After the fix and a restart, the stranded uploads were processed within 20 s of the first redelivery.

## Recovery times on this machine

| Event | Time until everything is processed and searchable |
| --- | --- |
| Worker killed | about 2 minutes (one lease) plus the interrupted steps' processing |
| OpenSearch restart | about 1.5 minutes (55 s to healthy, then retries) |
| Object store outage of 30 s | about 1 minute after it returned |
