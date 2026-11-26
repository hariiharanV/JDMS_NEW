# 0013 — Approval workflows on embedded Flowable

- Status: Accepted
- Date: 2026-10-24

## Context

Spec §4.4 needs three reusable approval processes (single, multi-stage with DMN routing, committee with quorum),
reject/return/resubmit, strict separation of duties, SLA flags and crash-safe completion. D-07 chose Flowable
(BPMN + DMN) embedded in the core. ADR-0009 already routes admin changes through a generic `pending_change`.

## Decision

- **Flowable 8.0.0** (Apache-2.0; the line for Spring Boot 4 and Jackson 3), process and DMN engines only (IDM,
  event registry, CMMN and the async executor are off). It shares the core's data source and Spring transaction
  manager, so a decision, its approval record, the subject's status change and the process step commit together
  or not at all. Flowable manages its own `ACT_*` tables (`database-schema-update: true`, its scripts cover
  PostgreSQL and Oracle); Flyway manages ours. For production, run Flowable's schema scripts before rollout and set
  the update mode to `false`.
- **Definitions** are versioned resources in `classpath:processes/` (`*.bpmn20.xml`, `*.dmn`), auto-deployed
  without a tenant with duplicate filtering, so each change creates a new version and running instances keep the
  version they started with. Instances, tasks and decisions are always tenant-scoped (`overrideProcessDefinitionTenantId`
  on start, `taskTenantId` on every query); a tenant-specific deployment overrides the shared default for that
  tenant. There is no runtime deployment API in the PoC.
- **Process expressions** may reference only three beans (`approvalPlan`, `approvalTaskSetup`, `approvalOutcome`)
  — never arbitrary application beans — and DMN tables are plain decision tables (rule 9).
- **Routing**: `single-approval` goes to checkers (`CHECKER`; pending changes use their handler's roles), SLA
  `jdms.workflow.default-sla-hours`. `multi-stage-approval` takes its ordered stages from DMN `approval-stages`
  (collect: order, key, name, candidate roles, SLA), evaluated at submission and again at every resubmission.
  `committee-approval` takes the committee code and SLA from DMN `approval-committee`; the voters are the
  committee's active members minus the maker, fixed at submission. Routing facts: document type and category,
  branch, classification and the custom fields (`amount` is 0 when absent). A submission that cannot be routed is
  refused (409 `APPROVAL_ROUTING_FAILED`, `COMMITTEE_QUORUM_UNREACHABLE`) and nothing is stored.
- **Committee tally** is counted from the approval history (`VOTE_APPROVE`/`VOTE_REJECT`): approved when approvals
  reach the quorum, rejected when rejections exceed members − quorum; open votes are then withdrawn. Concurrent
  votes cannot miss each other because they update the same multi-instance counters, so one fails with an
  optimistic-locking conflict; the task service retries such conflicts (up to 3 times, then 409 `TASK_CONFLICT`).
- **Separation of duties in the task service** (not only the UI), checked in this order: the maker never decides
  their own item at any stage (403 `TASK_MAKER_EXCLUDED`); the task must be offered to or claimed by the caller and
  the subject visible to them (else 404); claimed by someone else → 409; a user decides at most one task per item
  per round (no double vote; successive stages need different approvers; 409 `TASK_ALREADY_DECIDED`); the subject
  owner's policy check (`APPROVE` on the document, or the pending change's `canDecide`). `REJECT` and `RETURN`
  need a comment.
- **Return/resubmit** for uploads and versions: `RETURN` puts the item in the maker's amend task (an uploaded
  document becomes `DRAFT` and its metadata can be changed directly, without a separate metadata approval);
  `RESUBMIT` starts a new round from the first stage. Committees vote approve/reject only; pending changes are
  approved or rejected (the proposer cancels and proposes again instead of amending).
- **Subjects** are owned by their modules through `workflow.api.ApprovalSubjectHandler` (documents: `DOCUMENT`
  for uploads, `VERSION` for new major versions); BPMN service tasks report `APPROVED`, `REJECTED`, `RETURNED`,
  `RESUBMITTED` to the owner in the deciding transaction. Pending changes (ADR-0009) now start a `single-approval`
  instance too, so they appear in `/tasks`; either `/tasks/{id}/complete` or `/admin/pending-changes/{id}/approve`
  decides them, and both close the task. Cancel and stale end the instance.
- **Approval history** (`approval_record`) is written in the deciding transaction with a unique `task_id`, so a
  retried or replayed decision can never be recorded twice; it carries round, stage, actor (ID and username),
  decision and comment, and is shown on the document (`GET /documents/{id}/history`).
- **Inbox**: candidate-group query (the caller's effective roles, tenant-scoped) refined per item by the
  subject's policy check and the separation-of-duties rules; at most 1,000 candidate tasks are examined per
  request (PoC volumes; B-13 measures). SLA status: `OVERDUE` after the due date, `DUE_SOON` in the last quarter of
  the stage's SLA.
- **Module boundaries**: `access` and `identity` implement workflow SPIs (`TenantPermissions`,
  `ApproverDirectory`) because they already depend on `workflow.api` (their change handlers).

## Consequences

- Admins assign processes per document type (`process_key`, B-02) and manage committees (`/admin/committees`);
  changing routing rules means deploying a new DMN version (no editor in the PoC).
- Named-user stages need a tenant-specific DMN with user IDs; the shared table routes by role only.
- Escalation notifications are out of scope (spec §4.4); overdue tasks are only flagged.
- A database outage mid-completion leaves the last committed state; the client retries (proven by test).
