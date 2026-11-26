# 0017 — Records: deletion, holds, retention and disposition

- Status: Accepted
- Date: 2026-10-25

## Decision

- **Soft delete in the document module** (`DELETE /documents/{id}`, `DELETE` permission): to the recycle bin
  (`DELETED`, previous status remembered); through a checker's approval when the type sets `delete_requires_approval`
  (pending change `DOCUMENT_DELETE`, history type `DELETE`). Refused with 409 `DOC_LEGAL_HOLD` /
  `DOC_DECLARED_RECORD` (explicit codes, not a bare 403), for pending documents and checked-out documents.
  `POST /documents/{id}/restore` (`RESTORE`) returns the previous status. The recycle bin is
  `GET /documents?status=DELETED`; search hides deleted and disposed documents by default.
- **Declared records**: a type with `declare_record_on_approval` declares the document a record when its version is
  approved (`RECORD_DECLARED`). Object-lock retention on the blob is set by the store once O-01 picks one; the
  behaviour is specified by a test tagged `requires-worm-store`, excluded until such a store exists.
- **Records module** owns policies, holds, events, batches and tombstones and works on documents only through
  `document.api.DocumentRecords` (holds flag, retention candidates, dispose). Retention policies are assigned to
  document types (`document_type.retention_policy_id`, written through `metadata.api.RetentionAssignments` when a
  policy change is approved); changes go through maker-checker (a second `MANAGE_RETENTION` holder).
- **Retention end** = trigger date + period (UTC): creation date, expiry date (none: not due), or the first
  `record_event` of the policy's event type for the document's account (`POST /records/events`, e.g.
  `ACCOUNT_CLOSED`). Computed when the disposition job runs, so policy and event changes apply without rewriting
  documents.
- **Legal holds** are direct actions of records officers (`MANAGE_HOLDS`, audited), by document IDs or by business
  key, limited to documents the officer can see. A document's hold flag is on while any active hold contains it.
- **Disposition**: the daily job (`jdms.records.disposition.cron`, also `POST /disposition-batches/prepare`) puts
  the due, unheld documents of each tenant in one pending batch; a records officer approves (`DISPOSE`), and each
  document is checked again (held, no longer due, already disposed → `SKIPPED`), its blobs (versions and
  renditions) destroyed first, then its metadata reduced (`DISPOSED`, versions `DESTROYED`) and a tombstone written
  (document ID, type, dates, batch and approver, version hashes). A failure leaves the item `FAILED` without
  stopping the batch. In the PoC every batch needs approval (`requires_approval` is stored for later automation);
  `REVIEW` policies are disposed when their batch is approved (the approval is the review).

## Consequences

- Disposition removes content permanently; WORM retention must have expired by then on the production store.
- Saved searches as hold sources (spec §4.8 "by search") are reduced to business keys in the PoC.
