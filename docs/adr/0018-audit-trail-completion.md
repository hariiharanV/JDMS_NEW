# 0018 — Audit trail completion: database grants, verification, queries, coverage

- Status: Accepted
- Date: 2026-10-25

## Context

B-11 finishes the audit trail started in B-01 (ADR-0008): the application database user must not be able to change
or delete audit rows, a job must detect tampering, administrators need an audit query API whose use is itself
audited, and every state-changing endpoint, view and download must provably write an event.

## Decision

**Two database users.** Flyway runs as the schema owner (`spring.flyway.user`, `JDMS_DB_OWNER_USERNAME`); the core
and the worker connect as the application user (`JDMS_DB_USERNAME`). Migration V11 grants the application user
`SELECT, INSERT, UPDATE, DELETE` on all tables, the same through default privileges for tables created by later
migrations, and `USAGE, CREATE` on the schema (Flowable manages its own `ACT_*` tables, ADR-0013). It then revokes
everything on the Flyway history table and `UPDATE, DELETE, TRUNCATE` on `audit_event` and `audit_verification`.
Only the owner can alter or drop tables or switch triggers off. A later append-only table must revoke the same way
in its own migration. If both users are the same login, V11 skips the grants with a warning: production must use two.
Locally, `deploy/postgres/create-app-user.sh` creates the application user when the container initialises; tests
create it with a random password (`TestcontainersConfiguration`, `WorkerContainers`).

**Chain position.** `audit_event.chain_seq` (1, 2, … per tenant) is assigned from `audit_chain_head.event_count`
under the existing head lock; V11 back-fills existing rows by following the hash links. It is not part of the hash:
verification walks events by position and checks every `prev_hash` link, so a reordered, removed or inserted row
still breaks the chain. The hash formula of ADR-0008 is unchanged.

**Verification job.** `AuditVerifier` reads the committed head (no lock), walks the tenant's events by position in
pages of 500 (read-only transactions), recomputes each hash, checks each link and the position sequence, then
compares the last event with the head. Events appended during the run are left for the next run. The first failure
is reported with its position, event ID and reason: `HASH_MISMATCH` (a row's fields changed), `LINK_BROKEN` (a row
was re-hashed, inserted or reordered), `SEQUENCE_GAP` (a row was removed, including at the end) or `HEAD_MISMATCH`
(the head was replaced). Each run stores an `audit_verification` row and writes `AUDIT_VERIFIED` (outcome `ERROR`
when broken; a broken chain is also logged as an error). It runs daily for every active tenant
(`jdms.audit.verification.cron`, default 04:00) and on `POST /audit/verification`.

**Queries.** `GET /audit` (filters: resource type and ID, actor, action, outcome, time range; newest first by chain
position; paged) and `GET /audit/verification` require tenant-wide `VIEW_AUDIT` (security admins in the baseline).
`GET /documents/{id}/audit` requires `VIEW_AUDIT` on that document but not `VIEW_METADATA`, because security admins
audit documents they may not read; a caller with neither gets 404. Every query writes `AUDIT_QUERIED` with its
filters in the same transaction. Filters are JPA criteria (bound parameters).

**What is audited.** Every successful `POST`, `PUT`, `PATCH` and `DELETE`, every integration call, every content read
(content, preview, thumbnail), every audit read, and opening a document (`GET /documents/{id}` writes
`DOCUMENT_VIEWED`; internal reads of the same view do not). Lists, search hits and admin reads are not audited
individually: search writes `SEARCH_PERFORMED` per query, and auditing every list row would serialise all reads of a
tenant on its chain head.

**Coverage proof.** A test-only MockMvc result handler (`AuditCoverage`) checks every request of the integration
suite: an audited endpoint that succeeds without an event carrying its `X-Request-Id` fails that test immediately.
`AuditCoverageIT` runs last (`@RunLast`) and fails for any audited endpoint of the application that no test called
successfully (it skips itself on a partial run that covers under 80% of the audited endpoints). Writing the handler
found gaps, now fixed: idempotent replays of uploads and new versions (`DOCUMENT_REQUEST_REPLAYED`), a repeated
check-out by the holder (`DOCUMENT_CHECKED_OUT` with `repeated`), and a disposition run with nothing due
(`DISPOSITION_NONE_DUE`) returned success without an event.

## Consequences

- An attacker with the owner's credentials can still rewrite the chain consistently from some point to the end
  (re-hashing every later row and the head); a hash chain alone cannot prevent that. Anchoring head hashes outside
  the database (for example in the object-lock store of O-01) is the follow-up; it is out of the PoC scope.
- Existing local databases need the application user created once (README) and `deploy/.env` needs the owner and
  application credentials (`deploy/.env.example`).
- Running Flowable's DDL as the owner (shipping its scripts in Flyway, `flowable.database-schema-update=false`) would
  remove the schema `CREATE` grant; considered for production hardening.
