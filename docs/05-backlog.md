# 05 — PoC backlog

Work top to bottom. An item may start when its dependencies are `done`. Update `Status` (`todo`,
`in-progress`, `done`, `blocked: <reason>`) and add notes as you go. Every item's done criteria must be
proven by automated tests unless stated otherwise. Spec references point to `02-poc-functional-spec.md` (§)
and `03-domain-model.md` (DM).

| ID | Item | Depends on | Status |
| --- | --- | --- | --- |
| B-00 | Repository, build, CI and local environment | — | done |
| B-01 | Tenancy, identity and request context | B-00 | done |
| B-02 | Metadata model administration | B-01 | done |
| B-03 | Blob storage, upload, versions, download | B-02 | done |
| B-04 | Authorization module | B-03 | done |
| B-05 | Approval workflows (Flowable) | B-04 | done |
| B-06 | Processing pipeline (worker) | B-03 | done |
| B-07 | Search | B-04, B-06 | done |
| B-08 | Frontend (React) | B-02 … B-07, in slices | done |
| B-09 | Integration API for FLEXCUBE and bank systems | B-04 | done |
| B-10 | Records: retention, legal hold, recycle bin, disposition | B-05 | done |
| B-11 | Audit trail with hash chain | B-01 (start early; extend with each item) | done |
| B-12 | Legacy migration tool | B-03, B-05, B-10 | done |
| B-13 | Performance, resilience and OCR benchmark | B-07, B-12 | done |
| B-14 | Oracle compatibility smoke run (optional in PoC) | B-03 | deferred |
| B-15 | Assisted indexing: upload fields suggested from the file (English) | B-06, B-08 | done |
| B-16 | Upload from a capture: no second transfer or second OCR | B-15 | todo |
| B-17 | Screens for committees and users' branch scope and clearance | B-05, B-08 | done |
| B-18 | Integration pack: OpenAPI description, FLEXCUBE integration guide, folder paths, own references | B-09 | done |
| B-19 | Folders screen, folder picker in access rules, image previews | B-02, B-08 | done |
| B-20 | Preview of TIFF and BMP originals | B-06 | todo |
| B-21 | Bulk upload (screen and integration API), optional bulk delete | B-03, B-09 | todo |
| B-22 | Standard reports | B-11 | todo |
| B-23 | Legacy-compatible FLEXCUBE endpoints (only if FLEXCUBE cannot adopt the new API; O-03) | B-09 | todo |
| B-24 | Approval flow designer (stages, thresholds, committees per type on screen) | B-05 | todo |
| B-25 | Bulk approval on screen | B-05 | todo |

---

## B-00 Repository, build, CI and local environment

Create the layout from `CLAUDE.md`: Maven parent with `jdms-core`, `jdms-worker`, `jdms-migration`, `jdms-contracts`; React app in `frontend/`; `deploy/docker-compose.yml` with PostgreSQL, Keycloak (realm import), OpenSearch, an S3-compatible store, ClamAV, Tika server and Gotenberg.

Done when:
- `mvn verify` builds all modules, runs a Testcontainers smoke test against PostgreSQL, and runs Spring Modulith `verify()`
- CI (self-hosted GitLab CI, ADR-0002) runs backend build, frontend lint/test/build, CycloneDX SBOM, dependency vulnerability scan, licence check that fails on non-allowed licences (see `CLAUDE.md`)
- Flyway runs from `db/migration/postgresql`; `db/migration/oracle` exists
- `docker compose up` brings every dependency up healthy; `README.md` explains local setup
- Structured JSON logging with request ID; `/actuator/health` and Prometheus metrics exposed on a management port

Notes (done 2026-10-24):
- Decisions taken for this item: Java 25 (ADR-0001), self-hosted GitLab CI (ADR-0002), SeaweedFS for local and
  test S3 because MinIO OSS is archived (ADR-0003), portability renames (ADR-0004), ClamAV as a network service
  (ADR-0005).
- Proven by `./mvnw verify`: `CoreSmokeIT` (Testcontainers PostgreSQL 18.6: Flyway baseline from
  `db/migration/postgresql`, health and Prometheus on the management port only, request ID echoed and present in
  JSON log lines, query strings not logged), `ModularityTest` (`verify()` and detection of all modules),
  `LayeringArchTest`, `RequestIdFilterTest`, worker and migration smoke tests. The Maven and npm licence checks
  were shown to fail when an in-use licence is removed from the allow-list.
- Verified by hand (not automated): `docker compose up --wait` brings all seven services up healthy; Keycloak realm
  imported; S3 rejects unsigned and wrong-secret requests; Tika extracts text; Gotenberg renders PDF; ClamAV
  detects EICAR; jdms-core starts with the `local` profile.
- The pipeline has not run on the GitLab instance yet: its first run needs a runner that allows privileged
  containers (Docker-in-Docker) for Testcontainers.
- Deferred to B-01: `tenantId`/`userId` in the logging context, realm roles and claims, `jdms-integration-test`
  client. The `contracts` package is detected by Modulith as a module; its records stay in the top-level package.

## B-01 Tenancy, identity and request context

Keycloak realm `jdms-local` with clients `jdms-web` (public, PKCE) and `jdms-integration-test` (client credentials). Token carries `tenant`, roles, `branch_scope`, `clearance`.

Done when:
- Every request has a `RequestContext` (tenant, subject, roles, branch scope, clearance, request ID); missing or unknown tenant → 401
- `user_profile` synced on first login; admin can set branch scope and clearance
- Hibernate filter or repository base class enforces `tenant_id` on every query; a test with two tenants proves no cross-tenant read or write is possible through any repository
- Tenant, branch, user_profile, role, user_role, delegation tables created (DM: tenant, identity)

Notes (done 2026-10-24):
- Decisions: tenant isolation (ADR-0006), identity attribute ownership (ADR-0007), audit recording started here
  (ADR-0008).
- Realm `jdms-local`: eight PoC roles, admin-only `tenant`/`branch_scope`/`home_branch`/`clearance` attributes,
  claim mappers, audience `jdms-api`, seven dev users in BANK1/BANK2 and the `jdms-integration-test` client. Secrets
  come from environment placeholders (`deploy/.env`).
- Proven by tests: `TenantIsolationIT` (every repository, two tenants: reads, derived queries, deletes, saves, bulk
  JPQL, composite foreign keys, no tenant bound); `AuthenticationIT` (8 kinds of rejected token, suspended tenant,
  RequestContext contents, first-login sync audited once, roles mirrored, profile authoritative over token, clients,
  tenant and user IDs in logs); `UserAdminIT` (allowed and denied matrix, other tenant 404, If-Match 428/412, unknown
  branch, self-change, audit before/after, effective on next request); `KeycloakIntegrationIT` (real Keycloak with
  the realm file: client credentials, authorization code + PKCE login, ID token rejected, unknown tenant rejected);
  `AuditTrailIT` (chain recomputes from PostgreSQL, linear under concurrency).
- Verified by hand against `docker compose`: the core starts with the local seed (BANK1, BANK2, branches) and
  accepts a real client-credentials token; tokens without the `jdms-api` audience are rejected.
- Interim until B-04: endpoints are authorised by role with `@PreAuthorize` (`SECURITY_ADMIN` for
  `/admin/users`); B-04 replaces this with `AccessPolicy` and the ArchUnit check.
- Deferred to B-02: maker-checker on scope and clearance changes (`pending_change`). Deferred to B-03: the
  `Idempotency-Key` replay store (the header is accepted; `If-Match` already prevents a double apply).
- Each authenticated request reads the tenant and profile (4–5 small queries). A cache with TTL ≤ 60 s and
  event-driven invalidation comes with B-04's revocation rule.
- No frontend login yet: the React login slice is part of B-08.

## B-02 Metadata model administration

Cabinets, folders, document types, field definitions, LOVs (DM: metadata), with maker-checker on admin changes (`pending_change`).

Done when:
- CRUD APIs per `04-api-outline.md` Administration; changes are pending until a second admin approves; the proposer cannot approve
- Field validation engine validates `custom_fields` against definitions (type, mandatory, length, LOV)
- Seed data: one tenant, cabinets `CUSTOMERS` and `LOANS`, document types `PASSPORT`, `NATIONAL_ID`, `ACCOUNT_OPENING_FORM`, `LOAN_AGREEMENT`, `COLLATERAL_DEED`, `SALARY_CERTIFICATE`, with realistic fields (see `06-legacy-jdms-reference.md` §3)

Notes (done 2026-10-24):
- Decisions: generic maker-checker with change handlers (ADR-0009); metadata model choices — type codes unique per
  tenant, `required_standard_fields`, whole-list field replacement, facet and value rules (ADR-0010).
- The B-01 user scope change now goes through maker-checker too (second SECURITY_ADMIN, never the target user).
- Proven by tests: `MetadataAdminIT` (lifecycles for cabinets, folders, document types with fields, field
  replacement, LOVs; proposer cannot approve; reject/cancel rules; one pending change per subject or code; stale
  approval → `STALE`; concurrent approvals apply once; role matrix incl. security admin and other tenant → 404),
  `UserAdminIT` (maker-checker for access), `CustomFieldValidatorTest` and `FieldDefinitionRulesTest` (type,
  mandatory, length, LOV, defaults, unknown keys, control characters, no value echo), `SeedDataIT` (local seed loads;
  each seeded type's fields pass the admin rules; realistic documents validate for all six types),
  `TenantIsolationIT` extended to the seven new entities.
- Verified by hand against `docker compose` with real Keycloak logins (PKCE): seed applied, `tenantadmin1` proposes a
  folder (202), cannot approve it (403), `tenantadmin2` approves, folder exists. Realm gained `tenantadmin2` and
  `secadmin2`.
- Deferred: user-facing browse endpoints `GET /cabinets` and `GET /cabinets/{code}/folders` to B-04, so they are
  trimmed by `AccessPolicy` from the start (admins browse through `/admin/...`); "folder has documents" check on
  folder delete and enforcement of `required_standard_fields` to B-03; Flowable routing of admin approvals and
  `/admin/process-assignments` to B-05; `retention_policy_id` stays empty until B-10 (no API field yet).

## B-03 Blob storage, upload, versions, download

`BlobStore` (S3) and `KeyService` (local KMS stub) interfaces; envelope encryption; document and version lifecycle (§4.2, §4.3, §4.5 download only).

Done when:
- Upload stores the encrypted blob under the key format in DM, records SHA-256, creates document + version 1.0, emits `DocumentCreated` via the outbox, in one transaction boundary (blob first, then metadata; orphan blobs detected by a reconciliation job)
- Check-out / check-in / cancel and new versions behave per §4.3; versions are immutable (test attempts to modify fail)
- Download streams and decrypts; SHA-256 verified on read; corrupted blob → error and `storage_status = MISSING` alert
- Upload size limits per cabinet enforced; `Idempotency-Key` honoured
- No filesystem paths or filenames used as identifiers anywhere

Notes (done 2026-10-24):
- Decisions: ADR-0011 (Tink streaming AES-GCM envelope encryption, local KMS stub, streaming multipart upload with
  FileUpload 2 milestone, blob key spreading from the UUID hash, MIT-0 allowed), ADR-0007 updated (recreated
  identity-provider users are refused, not merged).
- Proven by tests: `DocumentUploadIT` (encrypted multi-segment round trip; key format and ciphertext bound to its key;
  approval types start pending; metadata validated before storing; classification and channel rules; cabinet size
  limit, quota, empty file with no blob left; idempotency replay/conflict/required/per-caller; duplicate warning;
  SCAN job queued through the outbox; corrupt, mid-stream-corrupt and missing blobs marked MISSING and alerted; orphan
  detection and deletion; interim access), `DocumentVersioningIT` (check-out conflicts, drafts, check-in from draft or
  file, cancel discards drafts and blobs, pending approval keeps the previous version current, restore, database-level
  immutability, metadata merge patch with masked sensitive audit, checker approval of metadata changes, folders with
  documents cannot be deleted), `EncryptedContentStoreTest`, `VerifiedContentTest`, `ClientFileInfoTest`,
  `TenantIsolationIT` for document, version, job and idempotency entities.
- Verified by hand against `docker compose`: 2.5 MB upload through the integration client (real Keycloak token),
  identical download, ciphertext in SeaweedFS under the documented key, SCAN job queued.
- Also done: checkers can use the pending-change inbox (sees only document metadata changes); folder delete checks for
  documents (B-02 deferral); required standard fields enforced (B-02 deferral); dev realm users have fixed IDs.
- Deferred: `POST /documents/{id}/links` and `GET /documents/{id}/history` (approval records, B-05); preview and
  thumbnail (B-06); delete/restore and recycle bin (B-10); multiple files per upload (one request per file).

## B-04 Authorization module

Implement §6 in `access` with `AccessPolicy.decide` and `searchFilter`; `access_rule`, `document_grant`; admin APIs via maker-checker.

Done when:
- Every existing endpoint calls `decide`; an ArchUnit test fails the build if a controller method lacks an authorization annotation or call
- Authorization matrix test: actors × actions × {own branch, other branch, other tenant, above clearance, legal hold, explicit deny, expired grant} — all expected outcomes pass
- Delegation and separation-of-duties rules tested
- Revocation: after removing a rule, access is denied within 60 seconds (cache invalidation test)
- Each decision returns the deciding rule; denied attempts are audited

Notes (done 2026-10-24):
- Design: ADR-0012 (policy order, TENANT scope, baseline rules, search filter, cache TTL ≤ 60 s + events,
  404-vs-403, module boundaries, delegation).
- Proven by tests: `PolicyEngineTest` (every step of §6 and its precedence, branch constraints, folder/type scopes,
  validity windows, grants valid/expired/revoked/bounded by clearance and deny, search filter);
  `AuthorizationMatrixIT` (147 API cases: 7 actors × 7 actions × own branch / other branch / above clearance, plus
  other tenant, legal hold, explicit deny with audited denial naming the rule, grants until expiry and revocation);
  `AccessRuleAdminIT` (maker-checker, no approving rules covering yourself, last-admin guard, validation, immediate
  revocation on rule removal, revocation within the TTL without an event); `DelegationIT` (delegation effective
  after approval and until revoked, no re-delegation, only held roles, ≤ 90 days, neither party approves, never more
  than the delegator still holds); `LayeringArchTest` fails the build for an endpoint without `@PreAuthorize`.
  Existing tests now run on the baseline rules (interim role checks removed).
- Verified by hand: baseline provisioned for BANK1 and BANK2 at startup; real logins see only what their clearance,
  branch and roles allow.
- Deferred: rule management UI (B-08); `REINDEX_ACL` jobs on `AccessRulesChanged` (B-07); legal holds and declared
  records are set by B-10 (the policy already honours the flags).

## B-05 Approval workflows (Flowable)

BPMN processes `single-approval`, `multi-stage-approval` (DMN routing), `committee-approval` (quorum); task APIs; `approval_record`; metadata change, delete and admin change approvals (§4.4).

Done when:
- Each process tested end to end: approve, reject (comment required), return and resubmit
- Maker can never complete their own task (enforced in the task service, not only the UI)
- Committee: quorum reached → approved; impossible to reach → rejected; double vote blocked
- DMN routing example: `LOAN_AGREEMENT` with amount > configured threshold adds a credit-committee stage
- Failure test: DB outage mid-completion → process resumes without duplicate approval records
- Process definitions versioned; running instances continue on their original version after a new deployment

Notes (done 2026-10-24):
- Design: ADR-0013 (Flowable 8 embedded, shared transaction, tenant-scoped instances, DMN routing, committee tally
  from the approval history, separation of duties in the task service, pending changes as single approvals).
- Proven by tests: `ApprovalWorkflowIT` (single: approve, reject with mandatory comment, return → amend →
  resubmit, maker with the checker role excluded, other-branch checkers and viewers, claims, overdue flag, other
  tenant; multi-stage: DMN adds the credit-committee stage above 100,000 for `LOAN_AGREEMENT`, stages in order,
  one user cannot approve two stages, rejection at stage 2, re-routing on resubmission; committee: quorum reached
  → approved and open votes withdrawn, quorum unreachable → rejected, no double vote or voting for others, maker
  never votes, unreachable quorum or missing committee refuses the upload; admin and metadata changes as tasks,
  history on the document); `ApprovalResilienceIT` (the database session is killed mid-completion after the
  approval record was written: nothing survives, the same task stays open, the retry is recorded once);
  `ProcessDefinitionsIT` (new deployment = new version, running instances stay on theirs, unchanged redeploys
  create no version, expressions cannot reach application beans); `CommitteeAdminIT`; `TenantIsolationIT` covers
  the new tables.
- Local realm: `committee1`-`committee3` (COMMITTEE_MEMBER) for the credit-committee stage and committee votes.
- Deferred: delete approvals with soft delete (B-10, which also honours `declare_record_on_approval`); replacing
  the file of a returned version; named-user stages (needs a tenant-specific DMN); a DMN editor or deployment API;
  escalation notifications (out of scope); inbox and history screens (B-08).

## B-06 Processing pipeline (worker)

`job` table with `SKIP LOCKED`; worker steps: antivirus (ClamAV), text extraction (Tika), PDF rendition and thumbnail (Gotenberg), OCR (Tesseract, English + Arabic) for scans and image-only PDFs; status fields per DM.

Done when:
- Each step idempotent and retryable with backoff; after `max_attempts` the job is `DEAD` and visible in an admin endpoint
- EICAR test file → `QUARANTINED`, download blocked, uploader notified (event)
- Malformed, oversized or zip-bomb files cannot crash the worker (resource limits, timeouts tested)
- Duplicate job delivery produces no duplicate renditions or index entries
- Renditions stored as separate blobs; originals never modified

Notes (done 2026-10-25):
- Design: ADR-0014 (shared `jdms-platform` module for encrypted storage and the job queue; the worker processes and
  reports `PROCESSING_RESULT` jobs, the core applies them; steps, limits, idempotency, preview rules).
- Proven by tests (real ClamAV, Tika with Arabic, Gotenberg, S3, PostgreSQL): `ProcessingPipelineIT` (digital PDF:
  text and thumbnail, no PDF copy; DOCX: PDF rendition; scanned PNG and image-only PDF: OCR finds the English and the
  Arabic line; EICAR: infected, processing stops; malformed PDF: FAILED without retries; file over the limit: FAILED;
  400 MB zip bomb: pipeline ends and the worker keeps working; duplicate delivery stores nothing new, completed
  versions are not rescanned; originals decrypt unchanged); `JobQueueIT` (concurrent claims disjoint, expired leases
  reclaimed, a hanging Tika → retries → DEAD with a FAILED result); `JobRunnerTest` (backoff, permanent failures,
  follow-ups); `ProcessingResultsIT` (results applied, preview and thumbnail served inline with authorization cases,
  quarantine blocks download and preview and notifies the uploader, late results for infected versions discarded,
  duplicate renditions discarded with their blobs, FAILED then DONE after a retry, `/admin/jobs` list and retry with
  denied cases); `TenantIsolationIT` covers `rendition` and `notification`.
- Deferred: `INDEX` jobs and `index_status` (B-07); per-user watermarks (viewer, B-08); PDF/A renditions; Arabic OCR
  accuracy benchmark (B-13).

## B-07 Search

OpenSearch index per tenant alias; mapping per DM; indexer job; query API with filters, facets and highlights (§4.6).

Done when:
- Metadata searchable immediately after commit; content after processing
- Leakage suite: a user without access never sees the document in results, totals, facet counts or highlights; snippets hidden without `VIEW_CONTENT`; results re-checked with `decide`
- Permission change (rule, hold, grant expiry) triggers ACL re-index; stale-ACL window ≤ 60 s proven by test
- Arabic and English analysers; search by CIF, account and custom searchable fields
- Full index rebuild from the database works and is documented

Notes (done 2026-10-25):
- Design: ADR-0015 (query-time access filter over indexed document attributes instead of indexed ACLs; content
  matched only within `VIEW_CONTENT`; policy re-check of every hit; segments instead of HTML snippets; indexing in
  the core after commit with versioned upserts; two-pass rebuild behind a per-tenant alias).
- Proven by tests against real OpenSearch (`SearchIT`): metadata searchable right after commit, by title, CIF,
  account, searchable custom text and LOV, date range; content searchable once processed, with English stemming and
  the Arabic analyser; sensitive fields not searchable; leakage suite (other branch, above clearance, other tenant:
  absent from results, totals and facet counts; metadata-only users never match on content and get no snippets; a hit
  whose current data denies access is dropped); rule removed on another node → gone within the TTL (< 60 s); grant
  valid → visible, expired → gone at once; branch change re-indexed; index deleted → rebuild via the admin endpoint
  restores it; invalid requests refused.
- "ACL re-index on permission change" is met without re-indexing (ADR-0015): rules and grants apply per query,
  holds do not affect viewing, document attribute changes re-index the document.
- Deferred: search screen (B-08); per-version entries; saved searches for legal holds (B-10).

## B-08 Frontend (React)

Screens in §8, built in slices alongside the backend items. Component library per O-07 default. i18n en/ar with RTL.

Done when:
- Playwright tests cover: login, browse, upload with dynamic form, preview (PDF.js), new version, approve/reject/return from inbox, search with facets, recycle bin restore, admin document type with fields
- Arabic locale renders RTL correctly on all PoC screens (visual check screenshots in CI artefacts)
- No server-built HTML; all text through i18n; keyboard navigation and visible focus on all screens
- UI hides actions the user cannot perform, but backend enforcement is the control (tests call APIs directly)

Notes (done 2026-10-25):
- Design: ADR-0019 (OIDC code + PKCE with `oidc-client-ts`; TanStack Query; PDF.js viewer on canvas; catalog,
  permissions and `allowedActions` endpoints as UI hints; registry visual identity; two Playwright suites).
- Proven by tests: `npm run e2e:stack` (real Keycloak, core and worker) covers login, browse, upload with the dynamic
  form, approve/reject/return from the inbox, PDF.js preview, new version, search with facets, delete with approval
  and recycle-bin restore, admin document type with fields approved by a second admin, hidden actions per user,
  keyboard (skip link, focus) and Arabic RTL on every screen with screenshots attached; `npm run e2e` (no backend:
  sign-in, RTL, remembered language, keyboard focus); Vitest (translation completeness, snippets as text, dynamic
  fields, error codes); `CatalogIT` (new endpoints: allowed and denied, other tenant, unauthenticated).
- Fixed on the way: approval during indexing returned 500 (lost optimistic check); all version writers now take the
  row lock (`VersionWriteConcurrencyIT`).
- Deferred: CI job for `e2e:stack` (needs a runner that starts the stack); retrieval requests screen (no backend in the
  PoC); per-user watermark burned into the file (viewer overlay only).
- Follow-up (2026-10-25): OFL-1.1 approved; the UI uses IBM Plex Sans and Plex Sans Arabic (ADR-0019).

## B-09 Integration API

`/integration/v1` per `04-api-outline.md`; OAuth2 client credentials; idempotency; rate limiting; mapping from the legacy contract (`06-legacy-jdms-reference.md` §4).

Done when:
- A mock FLEXCUBE caller (test) uploads by CIF/account, lists by business keys, downloads, and receives proper problem+json errors for invalid type, missing fields, no permission and duplicates
- Replayed request with the same `Idempotency-Key` returns the original response; different payload with the same key → 409
- Client identity, not caller-supplied user IDs, determines permissions
- Failure scenarios tested: S3 down, search down (upload still succeeds; indexing retried), timeout handling
- Stretch: legacy-contract adapter behind a feature flag

Notes (done 2026-10-25):
- Design: ADR-0016 (integration face of the document module; clients only; refused impersonation fields; legacy
  mapping; one-call idempotent new version; per-client token bucket; 503 for object-store outages).
- Proven by tests (`IntegrationApiIT`, mock FLEXCUBE client): upload by customer number, account and contract;
  list by each business key; metadata (CIF, default title, source system); download; new version and its replay;
  problem+json for unknown type, missing mandatory field, missing external system, missing idempotency key, missing
  business key, unknown document; a client without UPLOAD is refused even when naming a maker (and naming one is
  refused outright); people cannot use the integration API; same key and payload → original response, other payload
  → 409; burst beyond the rate → 429 with `Retry-After`; every call audited with the client ID; object store paused
  → 503 then the retry with the same key succeeds; search paused → upload succeeds, search 503, document searchable
  once search is back (INDEX job).
- Deferred: the legacy-contract adapter (stretch; needs O-03 payloads); `DELETE` (B-10).

## B-10 Records

Retention policies, legal holds, recycle bin, disposition batches, tombstones (§4.7, §4.8).

Done when:
- Delete/restore with approval rules; held or declared documents cannot be deleted (API and UI)
- Disposition job lists eligible documents; approval by records officer; execution destroys blobs and writes tombstones; audit events written
- Event-based retention (`ACCOUNT_CLOSED`) starts the clock for linked documents
- Object-lock behaviour tested against the candidate store from O-01 when available (compliance-mode delete fails even for storage admin); until then, the test is tagged `requires-worm-store`

Notes (done 2026-10-25):
- Design: ADR-0017 (soft delete with approval and explicit hold/record codes; records module over
  `document.api.DocumentRecords`; retention computed at disposition time; holds by ID or business key; two-step
  disposition: blobs destroyed, then tombstone).
- Proven by tests: `RecordsIT` (delete → recycle bin → restore by a records officer only; delete needing a checker's
  approval via the task inbox and shown in the history; held and declared documents refused, release allows
  deletion; retention policy maker-checker and validation; disposition prepares only due unheld documents, approval
  by another officer destroys the blob in S3, marks versions DESTROYED, reduces the metadata, writes the tombstone
  with hashes and the audit events, content then 410; a hold placed after preparation skips the document; account
  closure starts event-based retention for that account only); `RetentionCalculatorTest`; `TenantIsolationIT`
  covers the seven new tables. `WormStoreIT` (tagged `requires-worm-store`) specifies the object-lock check.
- Deferred: holds by saved search (business keys meanwhile); automatic disposition for policies without approval;
  records screens (B-08); object-lock retention on declaration (O-01).

## B-11 Audit trail

`audit_event` with per-tenant hash chain, verification job, query API (§4.11).

Started in B-01 (ADR-0008): table, `AuditTrail` API, per-tenant hash chain, events for profile, role and access
changes. B-02 to B-05 added events for metadata changes, uploads, versions, downloads, access decisions, approval
processes (`APPROVAL_STARTED`, `APPROVAL_DECIDED`, `APPROVAL_RESUBMITTED`, `APPROVAL_<outcome>`, `TASK_CLAIMED`),
document and version outcomes and committee changes. Remaining: the items below.

Done when:
- Every state-changing endpoint and every view/download writes an event (test helper asserts this for each endpoint)
- Application DB user cannot `UPDATE` or `DELETE` audit rows (DB grant test)
- Tampering with a row is detected by the verification job
- Audit query API with filters; reading audit is itself audited

Notes (done 2026-10-25):
- Design: ADR-0018 (schema owner runs Flyway, application user has DML only and INSERT/SELECT on audit tables;
  `chain_seq` per tenant; verification walks the chain by position; query API with `AUDIT_QUERIED`; document views
  audited as `DOCUMENT_VIEWED`; MockMvc-wide coverage check).
- Proven by tests: `AuditCoverage` (every successful write, view, content read, audit read and integration call in the
  whole integration suite has an audit event with its request ID) and `AuditCoverageIT` (run last: every such
  endpoint was exercised); `AuditGrantsIT` (the application user is neither superuser nor owner; `UPDATE`, `DELETE`,
  `TRUNCATE`, `ALTER`, `DROP` on audit tables and any access to the Flyway history are refused with SQLSTATE 42501);
  `AuditVerificationIT` (intact chain valid; changed row → `HASH_MISMATCH`, re-hashed row → `LINK_BROKEN`, removed
  row or cut end → `SEQUENCE_GAP`, replaced head → `HEAD_MISMATCH`, each with its position; other tenants unaffected;
  daily job; `VIEW_AUDIT` only); `AuditQueryIT` (filters, paging, newest first, tenant scope, reads audited, denials
  findable, invalid queries refused, role matrix, document audit for auditors only); `AuditTrailIT` (positions follow
  the chain); `TenantIsolationIT` covers `audit_verification`. The worker's tests also run as the application user.
- Deferred: anchoring chain heads outside the database (O-01 store); exporting legacy `CM_HISTORY` (B-12); audit
  query screen (B-08).

## B-12 Legacy migration tool

`jdms-migration` CLI: reads legacy JDMS tables (Oracle in real runs; PostgreSQL fixture in tests) and files, decrypts legacy files, maps to the new model, writes through the core's import service, produces a reconciliation report.

Done when:
- Mapping per `06-legacy-jdms-reference.md` §5 implemented for documents, custom fields, versions, workflow history, document types and fields, folders, permissions
- Legacy decryption isolated in one class; key read from a secret at runtime; never logged (test asserts logs contain no key material)
- Re-encryption with the new tenant key; SHA-256 of decrypted content recorded before and after
- Reconciliation report per run: counts per cabinet/type, checksum matches, metadata field-by-field diffs, version counts, approval history counts, permission comparison per user sample, exceptions list (corrupt, missing, duplicate, undecryptable) — nothing skipped silently
- Idempotent re-runs (already migrated items skipped by `legacy_document_id`); delta mode for changes after a cut-off timestamp

Notes (done 2026-10-25):
- Design: ADR-0020 (tool reads and decrypts; core imports through `/integration/v1/migration` with the normal
  validation, encryption, processing, audit and tenant isolation; catalog through maker-checker proposals;
  per-document legacy role permissions as document rules; reconciliation per run).
- Proven by tests: `MigrationEndToEndIT` (legacy PostgreSQL fixture and AES-encrypted file tree into the real core,
  in-process): catalog proposals become real only after approval (types with fields, nested folders, folder
  permissions); 11 legacy documents end in exactly one outcome each (migrated with versions and history, deleted into
  the recycle bin, duplicate content flagged, missing file, undecryptable, corrupt, in workflow, type not migrated,
  invalid metadata, unmapped role); SHA-256 of every decrypted file equals the core's; no metadata differences;
  permissions of sampled users compared (none missing, extras listed); re-run idempotent; delta mode imports new
  documents and reports changed migrated ones; no key material in any log line. `LegacyImportIT` (import API:
  versions, history, rules, idempotency per tenant, checksum and file-count mismatches, validation, only the
  migration client, access check for hypothetical users, other tenants invisible); `CoreClientTest` (multipart order
  and retries on the tool's production classpath); `DocumentMapperTest`, `LegacyFileCipherTest`,
  `LegacyFileStoreTest` (paths cannot leave the files root). Real stack: catalog and run against Keycloak, the core
  and the worker; a migrated PDF was scanned, rendered, previewed and found by content search.
- Deferred: legacy layout and permission codes to confirm per bank against samples; retrieval requests and links
  (no PoC backend); exporting `CM_HISTORY` for evidence; streaming very large files (read into memory up to
  `max-file-mb`); in-flight legacy approvals (finished in JDMS before cut-over).

## B-13 Performance, resilience and OCR benchmark

Done when:
- Data generator creates 1 million synthetic documents with metadata and text
- Load test (Gatling or k6) measures the §7 targets; results committed under `docs/perf/`
- Resilience tests: kill worker mid-job, restart OpenSearch, S3 unavailable — no data loss, correct statuses
- OCR benchmark harness: runs Tesseract and PaddleOCR on the sample set (O-02), reports character accuracy, field extraction accuracy and time per page, English and Arabic
- Backup and restore rehearsal of PostgreSQL + object store + keys documented with timings (RPO/RTO evidence)

Notes (done 2026-10-25):
- Design: ADR-0021 (test tooling apart from the product: `backend/jdms-perf` generator and Gatling simulations,
  `perf/` OCR benchmark image, resilience rehearsal, backup and restore scripts; Gatling's report licence approved
  for the test-only module; synthetic tenant PERF written directly in the core's tables and encrypted storage).
  Results and how to run them: `docs/perf/` (`results.md` is the summary against §7).
- Measured on one shared 2-vCPU machine running the whole stack (lower bounds, not sizing): 1,000,062 documents
  generated at 488,000 per hour and indexed by a full rebuild in 54 minutes; search p95 705 ms at 5 searches/s and
  835 ms at 7/s (saturates by 10/s); content searchable p95 7 s (digital) and 18 s (scans with OCR); first-page preview
  p95 583 ms during ingest and 122 ms under preview load; revocation effective at the first check after approval; one
  worker processes about 2,760 documents per hour (100,000 per hour needs about 72 vCPUs of processing: scaling
  estimate in `results.md`); metadata searchable p95 2.3 s after the upload returns (near-real-time; target wording
  for O-06).
- Fixed because of the measurements (findings in `docs/perf/results.md`): index rebuild (bulk, batched loads,
  concurrent reads, version-based catch-up, failed rebuilds drop their index); job lease heartbeat with 2-minute
  leases (long jobs ran twice, dead workers' jobs waited 15 minutes); cabinet quota counter instead of a sum over
  all versions on every upload (V13); outbox redelivery of failed listener deliveries (stranded uploads were never
  processed); 503 with `Retry-After` instead of 500 or 401 when no database connection is available; 10 job attempts
  (index jobs died during a two-minute OpenSearch restart); PostgreSQL `shm_size` in compose.
- Proven by tests: `SearchIndexerRebuildTest`, `SearchIT.batchedLoadingMatchesSingleLoadingAndStaysInTheTenant`,
  `JobRunnerTest` and `JobQueueIT` (heartbeat keeps a long job's lease), `WorkerResilienceIT` (worker dead mid-step,
  object store outage), `DocumentUploadIT` and `DocumentVersioningIT` (concurrent uploads cannot both take the last
  of a quota; usage equals the sum of versions), `TenantIsolationIT` (new entity), `EventRedeliveryIT`,
  `ProblemSecurityHandlersTest`, `ProblemDetailsAdviceTest`, `SyntheticCorpusTest`. Rehearsals on the real stack:
  worker SIGKILLed with steps running (taken over after one lease, all processed once in 125 s), OpenSearch restarted
  (uploads unaffected, no dead index jobs), object store stopped for 30 s (503, idempotent retries, nothing lost).
- OCR (O-02 default, synthetic pages): Tesseract `eng+ara` 0.2% / 5.1% CER and 97.5% / 80.8% field accuracy
  (English / Arabic) at 2 s per page; PaddleOCR on CPU equal in English but 36% CER in Arabic (drops Western digits
  in Arabic lines) at 10 to 12 s per page. Tesseract stays (`docs/perf/ocr-benchmark.md`).
- Backup and restore rehearsal (`docs/perf/backup-restore.md`): `pg_dump` (42 s) and an S3-level copy of 2 million
  encrypted objects (49 min); restore into an empty stack: database 2 min, objects 75 min, core 1 min; row counts
  identical, 1,000 of 1,000 sampled versions decrypt and match with the master key and none with another key, audit
  chains VALID, downloads match, search rebuilt. RTO on this box about 80 minutes for documents and about 2 h 15 min
  with search; RPO is the last backup (production needs PITR and object replication).
- Deferred: a run on representative hardware (O-06); batched grant checks and capped totals in search; the bank's
  sample set for OCR (O-02); point-in-time recovery (WAL archiving) and object-store replication or versioning for a
  production RPO; CI job for the load tests (needs the full stack).

## B-14 Oracle compatibility smoke run (optional during PoC)

Done when:
- A weekly CI job starts the free Oracle Database container, runs Flyway Oracle scripts (generated so far) and the repository integration tests
- Failures are reported, not blocking the main build

Notes (deferred 2026-10-25): skipped for now by the product owner. Needs approval of the Oracle Database Free
container and the Oracle JDBC driver licences (Oracle Free Use Terms, not on the CLAUDE.md list; test-only, never
shipped) and Oracle versions of migrations V1 to V13 in `db/migration/oracle`.

## B-15 Assisted indexing: upload fields suggested from the file (English)

Added 2026-11-24 at the product owner's request, ahead of the RFP roadmap (23.71 AI auto-population, Phase 3; 23.38
zone OCR, Phase 4+): a rule-based first version with the PoC's engines. Design: ADR-0024.

Done when:
- Attaching a file and choosing a type on the Upload screen reads the file in the worker (antivirus, text, OCR for
  scans and image-only PDFs) and fills in the standard banking fields and custom fields it finds, in English
- Only fields the user has not touched are filled; each says it came from the document until the user changes it
- Suggestions go only to the user who attached the file, with upload permission for the type and branch; other
  users and tenants get 404; values are never in plain columns, job payloads, logs or the audit trail
- The file is deleted once read; captures expire; an infected file suggests nothing
- The upload itself is unchanged

Notes (done 2026-11-24):
- API: `POST /api/v1/captures` (202), `GET /api/v1/captures/{id}`, `DELETE /api/v1/captures/{id}`; migration V15
  (`capture`); job types `CAPTURE` (worker) and `CAPTURE_RESULT` (core); settings `jdms.capture.ttl` (1 h),
  `jdms.capture.max-pending` (5), `jdms.capture.expiry-cron`.
- Proven by tests: `FieldSuggesterTest` (labels and values on one line, several per line, next line, running text
  ignored, wrong forms rejected, generic labels and conflicts, dates, IBAN, custom fields by label, key and type, list
  values, bounds); `CaptureReaderIT` (real ClamAV and Tika/Tesseract: a scanned English form gives name, CIF,
  account, expiry date, a number and a list value; a digital PDF without OCR; EICAR infected; no labels, no
  suggestions; no second read); `CaptureIT` (suggestions reach the owner only, file deleted once read, values not in
  columns or audit, duplicate and late results dropped with their blobs, denied without `UPLOAD`, outside the branch
  scope, above clearance, for another user and another tenant, permission re-checked on read, idempotent start,
  pending limit, validation before storage, expiry, reconciler); `TenantIsolationIT` (new entity);
  `upload.test.tsx` (fields filled and marked, typed values kept, list values outside the list skipped, title from
  the customer, infected file, capture discarded on leaving).
- Follow-up (2026-11-24): passports read from their machine-readable zone and bilingual labels (`MrzReaderTest`,
  `FieldSuggesterTest`); a real scanned passport gave name, passport number, nationality and expiry date, all HIGH.
- Deferred: Arabic labels and digits; national ID cards' zones (TD1); reusing the capture's blob and text for the uploaded version (the file is sent
  twice, a scan read twice); per-type templates or zones; layout-aware or AI extraction (RFP 23.71, 23.38); a
  Playwright journey against the running stack.

## B-16 Upload from a capture: no second transfer or second OCR

Follow-up to B-15 (ADR-0024 consequences), deferred on 2026-11-24 for the customer demo. Today the browser sends
the file twice (once as a capture to be read, again with the upload) and a scan is virus-scanned and OCR'd twice.
The user sees no extra step; the cost is network time on slow branch links and worker time (about 2 s per scanned
page).

Done when:
- `POST /documents` accepts a `captureId` (owned by the caller, not expired, status `DONE`) instead of a file part;
  the capture's blob becomes version 1.0's blob; the capture is consumed in the same transaction
- The capture keeps its file until it is uploaded or expires (today the file is deleted once read)
- The text (or OCR) from the capture is kept as the version's `TEXT` rendition and the pipeline skips EXTRACT/OCR;
  the virus scan result is reused or the scan rerun (decide in an ADR)
- Idempotency, quota, duplicate-content warning, approval start and audit behave exactly as for a file upload;
  authorization re-checked for the final placement; tests for reuse, another user's capture, expired capture,
  replay with the same key
- The Upload screen sends the capture ID when the capture is `DONE`, and the file otherwise

## B-17 Screens for committees and users' branch scope and clearance

Added 2026-11-24 at the product owner's request: the bank team could not set up committee approval or a user's
branches without the API (Approval Workflow Setup Guide, section 8).

Done when:
- Tenant administrators list, create, change (members, quorum) and deactivate committees on screen; members are
  chosen from the tenant's users by name; a member without a voting role is flagged
- Security administrators list users with roles, branches and clearance and propose a change of branches or clearance
  on screen; nobody changes their own
- Every change stays a proposal a second administrator approves (unchanged backend rules)

Notes (done 2026-11-24):
- Backend additions: `GET /api/v1/branches` (any user of the tenant: code and name), `roles` on `/admin/users`
  views (mirrored from the token at sign-in, for display only), `GET /api/v1/admin/committees/candidates`
  (`ADMIN_METADATA`: active users of the tenant with their roles; integration clients left out).
- Screens: Admin > Committees (`/admin/committees`), Security > Users (`/admin/users`).
- Proven by tests: `UserAdminIT` (roles on the view; branches for any user of the tenant, only its own, 401 anonymous),
  `CommitteeAdminIT` (candidates for tenant admins only, other tenants and clients left out, denied for security
  admins, checkers and makers), `people.test.tsx` (committee proposal body, voting-role warning, deactivation with
  If-Match, users without system clients, scope proposal with the freshly read version, no self-change),
  `foundations.test.tsx` (menus).
- Deferred: creating users or assigning roles in JDMS Next (they stay in Keycloak, ADR-0007); the routing table
  still sends every Committee-route type to `CREDIT_COMMITTEE`.

## B-18 Integration pack: OpenAPI description, FLEXCUBE integration guide, folder paths, own references

Added 2026-11-24 at the product owner's request, so a core banking vendor can integrate (O-03 still open: the guide's
example is a customer creation screen filing a KYC document). Design: ADR-0025.

Done when:
- An OpenAPI 3 description of the integration API that a build check keeps in step with the code
- A Word guide for the vendor: client set-up, tokens, calls, retries, errors, legacy mapping, test server
- A document can be filed into a folder named by its path, and found again by the caller's own reference

Notes (done 2026-11-24):
- `docs/api/jdms-integration-v1.openapi.yaml`; `IntegrationApiSpecTest` (endpoints and upload fields equal the
  controller's); all `$ref`s resolve.
- `folderPath` on `POST /integration/v1/documents`; `externalRef` on `GET /integration/v1/documents`; V16 index.
- Proven by `IntegrationApiIT.aCustomerCreationScreenFilesIntoANamedFolderAndFindsTheDocumentByItsOwnReference`
  (filed in the folder named by path, found by its reference only, content back, unknown path 422 before storage,
  another tenant's client finds nothing).
- Guide: `docs/manuals/JDMS-Next-FLEXCUBE-Integration-Guide.docx` (`integration.js`).
- Deferred: a folder administration screen; an OpenAPI description of the web application's API; validation of the
  spec with an external OpenAPI linter (download blocked on this machine).

## B-19 Folders screen, folder picker in access rules, image previews

Added 2026-11-24 from the testing team's observations (docs/reviews/JDMS-Next-Observations-Response-2026-11-24.docx).

Notes (done 2026-11-24):
- Admin > Folders (`/admin/folders`): the folder tree of each cabinet; create (top level or sub-folder), rename, move,
  delete (empty folders only); every change a proposal for a second tenant admin (existing backend, B-02).
- Access rules with scope Folder pick the folder from the cabinet's tree instead of a pasted ID.
- The viewer shows image originals (JPEG, PNG, GIF, WebP) itself; it used to pass them to PDF.js, which reads only
  PDFs, so image previews failed.
- Proven by `folders.test.tsx` (tree after load, sub-folder proposal, rename with the current version, no slash in
  names, delete only when empty), `viewer.test.tsx` (image preview), `foundations.test.tsx` (menu).

## B-20 to B-25 (from the testing observations, 2026-11-24)

Scopes are in docs/reviews/JDMS-Next-Observations-Response-2026-11-24.docx section 5. B-20 TIFF/BMP previews
(worker converts to a PDF rendition); B-21 bulk upload with a metadata sheet and a batch API; B-22 standard reports;
B-23 legacy-compatible FLEXCUBE endpoints, only if FLEXCUBE cannot adopt the new API; B-24 approval flow designer
(DMN tables on screen); B-25 approving several tasks at once on screen.

