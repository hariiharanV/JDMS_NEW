# Backup and restore rehearsal (B-13)

Done criterion: backup and restore of PostgreSQL, the object store and the keys, documented with timings (RPO and RTO
evidence). Rehearsed on 2026-10-25 on the 2-vCPU machine of [results.md](results.md), with the tenant of a million
documents, using `perf/backup/backup.sh`, `restore.sh` and `verify-restore.sh`.

## What is backed up, and how

| Part | Method in the rehearsal | Notes |
| --- | --- | --- |
| PostgreSQL | `pg_dump` (custom format, light compression) of the database, and the roles without passwords | A logical snapshot at the moment the dump starts |
| Object store | S3-level copy of the bucket with rclone, objects copied as they are (still encrypted) | Taken after the dump: blobs are written before their row commits and never change, so every blob a dumped row refers to is in the copy |
| Keys | Not in the backup set. The tenant keys are derived from the KMS master key, which the secret store (KMS, Vault, HSM) backs up under its own controls; the manifest records only the key's fingerprint, so a restore can check it was given the right key | Without the key the backup is unreadable: proven below |
| Search index | Not backed up: rebuilt from the database after a restore (B-07) | OpenSearch snapshots would shorten the RTO (below) |
| Keycloak | Out of scope: the bank's identity provider has its own backup | |

## Timings

| Step | Time | Size |
| --- | --- | --- |
| Backup: database dump | 42 s | 527 MB (1,001,492 documents, 1,001,502 versions, 1,002,961 renditions, 39,341 audit events) |
| Backup: object copy | 48 min 46 s | 2,004,463 objects, 3.7 GB |
| Backup: total | 50 min 42 s | |
| Restore: empty PostgreSQL, SeaweedFS, OpenSearch started | 1 min 34 s | |
| Restore: database (`pg_restore`, indexes and constraints included) | 1 min 56 s | |
| Restore: objects (into an empty SeaweedFS; writes are slower than the backup's reads) | 75 min 29 s | 2,004,463 objects |
| Core started on the restored services (Flyway validated 15 migrations, nothing to apply) | 54 s (application 26 s) | |
| Search index rebuilt (measured on the same data, B-13 rebuild) | 53 min 37 s | 1,000,062 documents |

## Checks after the restore

| Check | Result |
| --- | --- |
| Row counts against the backup's manifest (documents, versions, renditions, audit events, last audit sequence) | all five identical |
| Content: 1,000 random versions decrypted with the master key and compared with the SHA-256 recorded at upload | 1,000 of 1,000 match |
| Content with a random key instead | 0 of 1,000 decrypt: the backup is useless without the key, which lives in the secret store |
| Audit hash chain verified by the restored core (`POST /api/v1/audit/verification`) | VALID for BANK1 (1,187 events) and PERF (38,153 events) |
| Downloads through the restored core's API, compared with the recorded SHA-256 | 10 of 10 (BANK1, as records1) and 10 of 10 (PERF, as perfmaker1); viewers got 403 as their role has no DOWNLOAD |
| Search: BANK1 index rebuilt on the empty restored OpenSearch, then searched | 91 documents in about 6 s; search answers |

## RPO and RTO

**RPO.** With these logical backups the recovery point is the start of the last backup: run nightly, up to 24 hours of
changes could be lost. The object copy is incremental after the first run (only new objects are copied; objects never
change), so it can run far more often than the first 49 minutes suggest. For a bank, the production target should be
minutes, which needs: PostgreSQL point-in-time recovery (continuous WAL archiving with a base backup, e.g. pgBackRest,
MIT licence; or the managed database's PITR), and object-store replication or versioning with object lock (O-01:
WORM), so that every committed blob already exists in a second location. Keys follow the KMS's own replication.

**RTO.** Measured here: about 80 minutes until documents can be opened, uploaded and approved again (services,
database, objects, core), and about 2 hours 15 minutes until search is complete (the rebuild of the index). On production
hardware and design the main terms shrink: a replicated object store is not copied back at all (the core is pointed at
the replica: minutes), the database restore scales with its storage, and OpenSearch snapshots restore the index in
minutes instead of rebuilding it (the rebuild stays the fallback, and it runs while the system is in use).

These are rehearsal figures for one machine and 1 million documents; O-06 should set the targets, and the production
runbook should be rehearsed on the target infrastructure.
