# 0008 — Audit recording starts in B-01

- Status: Accepted
- Date: 2026-10-24

## Context

CLAUDE.md rule 10 requires an audit event for every state change, and B-01 already changes state (profile creation,
role mirroring, branch scope and clearance changes). The backlog lists B-11 (audit trail) as "start early; extend
with each item".

## Decision

B-01 delivers audit recording: the `audit_event` table, the `AuditTrail` API, and the per-tenant hash chain.

- An event is written in the caller's transaction, so it commits exactly when the change does.
- `hash` = SHA-256 over every field, each encoded as `<length>:<value>;`, plus `prev_hash`; the first event of a
  tenant uses 64 zeros. Times are truncated to microseconds (PostgreSQL precision) before hashing.
- `details` is canonical JSON (keys sorted at every level). PostgreSQL returns `jsonb` reformatted, so verification
  must re-canonicalise the stored value (`AuditHasher.canonicalJson(String)`), never hash the column text.
- Appends are serialised per tenant by locking that tenant's `audit_chain_head` row (`SELECT … FOR UPDATE`).

Still for B-11: the verification job and endpoint, the audit query API, the INSERT/SELECT-only database grant, the
"every endpoint writes an event" test helper, and denied-access events (with B-04).

## Consequences

`AuditTrailIT` proves that the chain recomputes from stored rows, stays linear under concurrent appends, and that a
changed field is detected.
