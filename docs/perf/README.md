# Performance, resilience and OCR evidence (B-13)

Results of each run are in this folder; the tooling is described in ADR-0021. Every run states its environment: the
numbers so far come from one shared development machine (2 vCPU, 22 GB RAM) that runs the whole stack (PostgreSQL,
OpenSearch, SeaweedFS, Keycloak, ClamAV, Tika, Gotenberg, core, worker) and the load generator together. They are
lower bounds, not sizing figures (O-06).

| File | What it holds |
| --- | --- |
| [results.md](results.md) | Summary against the §7 targets, findings and what changed because of them |
| [ocr-benchmark.md](ocr-benchmark.md) | Tesseract against PaddleOCR, English and Arabic |
| [resilience.md](resilience.md) | Worker killed mid-job, OpenSearch restarted, object store unavailable |
| [backup-restore.md](backup-restore.md) | Backup and restore rehearsal with timings (RPO and RTO evidence) |
| `gatling/` | Gatling summaries of the load runs |

## How to run

All commands from the repository root with the local stack running (CLAUDE.md "Commands") and the environment
loaded: `set -a; . deploy/.env; set +a`. JDK 25 as `JAVA_HOME`.

### 1. Synthetic data (1 million documents)

```
cd backend && ./mvnw -pl jdms-perf -am install -DskipTests && cd ..
java -jar backend/jdms-perf/target/jdms-perf-*-exec.jar generate --jdms.perf.documents=1000000
```

Creates the tenant `PERF` (20 branches, the BANK1 local catalog) and fills it with synthetic English and Arabic
banking documents: one approved version each, text original and extracted-text rendition, both envelope-encrypted in
the object store. Resumable: a re-run continues from the tenant's current count. Then restart the core (it gives the
new tenant its baseline access rules) and rebuild the tenant's index as `perfadmin1`:
`POST /api/v1/admin/search/rebuild`. The dev realm has the PERF users: `perfviewer1` (VIEWER, all branches,
CONFIDENTIAL), `perfbranch1` (VIEWER, branches 002 to 004, INTERNAL), `perfmaker1`, `perfadmin1`, `perfsec1`,
`perfsec2` (password `JDMS_DEV_USER_PASSWORD`).

### 2. Load tests (Gatling)

```
cd backend
./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.SearchSimulation -Drate=10 -Dduration=300
./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.IngestSimulation -Drate=0.5 -Dduration=600
./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.PreviewSimulation -Drate=5 -Dduration=180
./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.RevocationSimulation -Drounds=3
```

Reports: `backend/jdms-perf/target/gatling/<simulation>-<timestamp>/index.html`; wall-clock latencies that span
several requests (upload until searchable, approval until revoked) in `<simulation>-latencies.json` next to them, and
`python3 perf/gatling-summary.py <report-dir> [<latencies.json>]` prints both as Markdown. Each simulation asserts its
§7 target and fails the run when it is missed.

Ingest throughput: upload without following each document (`-Dfollow=false`), then read the pipeline's drain rate:

```
./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.IngestSimulation -Drate=1 -Dduration=300 -Dfollow=false
perf/ingest-throughput.sh <instant the run started, e.g. 2026-10-25T17:30:00Z>
```

For the measurements, OpenSearch ran with a 2 GB heap:
`docker compose -f deploy/docker-compose.yml -f perf/docker-compose.perf.yml up -d --wait opensearch`.

### 3. OCR benchmark

```
docker build -t jdms-ocr-bench:1 perf/ocr-benchmark
docker run --rm --network host -v "$PWD/build/ocr:/work" jdms-ocr-bench:1 samples.py /work/samples
docker run --rm --network host --memory 6g -v "$PWD/build/ocr:/work" jdms-ocr-bench:1 benchmark.py /work/samples /work/results
```

Uses Gotenberg (sample rendering) and Tika (Tesseract) from the local stack.

### 4. Resilience rehearsal

```
python3 perf/resilience/rehearsal.py build/resilience --scenarios worker,opensearch,s3
```

Kills the worker with SIGKILL (`perf/resilience/worker.sh`, which also restarts it), restarts OpenSearch and stops
SeaweedFS for 30 seconds, each during uploads, then checks every upload exists once, is processed and searchable, and
no job died. Needs `docker` and `python3` (standard library only).

### 5. Backup and restore rehearsal

```
perf/backup/backup.sh /path/to/backup
JDMS_RESTORE_DIR=/path/to/restore perf/backup/restore.sh /path/to/backup
perf/backup/verify-restore.sh /path/to/backup 1000
```

`verify-restore.sh` compares row counts with the backup's manifest and decrypts a random sample of versions with the
master key (all must match their SHA-256) and with a random key (none may decrypt). Then start a core against the
restored services (ports 15432, 18333, 19200) and check the audit chain, downloads and search as described in
[backup-restore.md](backup-restore.md).
