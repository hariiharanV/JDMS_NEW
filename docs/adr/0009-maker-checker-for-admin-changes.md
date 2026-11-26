# 0009 — Maker-checker for administrative changes

- Status: Accepted
- Date: 2026-10-24

## Context

Spec §4.1 and the API outline require every admin change (cabinets, document types, fields, LOVs, access rules,
user scope…) to be approved by a second admin; the proposer can never approve. Flowable arrives only in B-05, but
B-02 already needs maker-checker, and B-01 deferred it for user scope changes.

## Decision

- A generic `pending_change` in the `workflow` module. Admin writes do not change anything: they validate the input,
  store a proposal and answer **202 Accepted** with the pending change (`Location: /admin/pending-changes/{id}`).
- Domain modules implement `workflow.api.ChangeHandler` (subject type, approver role, `apply`), so `workflow` never
  depends on them. `PendingChangeService` finds handlers lazily, which also breaks the bean cycle.
- Rules for every subject type: the proposer cannot approve or reject (they may cancel); the approver must hold the
  handler's role (`TENANT_ADMIN` for metadata, `SECURITY_ADMIN` for user access); handlers can exclude more users
  (the user whose access changes); reject needs a comment; changes of types a caller cannot approve are not visible
  to them (404).
- Concurrency: at most one pending change per subject and per claimed natural key (a code being created), enforced
  in code and by partial unique indexes; updates carry the subject's version from `If-Match`; on approval the handler
  re-checks everything and returns **stale** instead of overwriting newer data — the change becomes `STALE` (committed)
  and the approver gets 409 `CHANGE_STALE`. Decisions lock the pending change row (`SELECT … FOR UPDATE`), so two
  approvers racing on one change apply it once; the other gets 409 `CHANGE_NOT_PENDING`.
- Audit: `CHANGE_PROPOSED/APPROVED/REJECTED/CANCELLED/STALE` on the pending change, plus one event for the applied
  change itself (actor = approver, proposer in the details).

## Consequences

- B-05 can route these approvals through the `single-approval` Flowable process (`process_instance_id` is reserved)
  without changing handlers.
- Oracle: the partial unique indexes become function-based unique indexes in the Oracle scripts.
