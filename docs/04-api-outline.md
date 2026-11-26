# 04 — API outline

Base path `/api/v1`. JSON only. Generate an OpenAPI 3.1 document from the code (springdoc) and keep it in
`docs/api/openapi.yaml` via CI; this outline is the contract to implement.

## Conventions

- Auth: `Authorization: Bearer <JWT>` from Keycloak. Tenant comes from the token (`tenant` claim), never from the request body or path.
- Integration clients: OAuth2 client credentials; their service account holds roles like a user.
- Writes accept `Idempotency-Key` (required on uploads and integration calls).
- Errors: `application/problem+json` with `type`, `title`, `status`, `detail`, `code` (stable, e.g. `DOC_LEGAL_HOLD`), `requestId`.
- Paging: `?page=0&size=50` (max 200); responses `{ "items": [...], "page": 0, "size": 50, "total": 1234 }` where `total` counts only permitted items.
- Concurrency: `ETag` / `If-Match` on updates to documents and admin objects.
- Every response carries `X-Request-Id`.
- Access tokens must have issuer = the configured Keycloak realm and audience `jdms-api`. A token whose `tenant` claim is missing, unknown or names a suspended tenant gets 401 (`AUTH_UNAUTHENTICATED`); so does a missing, expired or badly signed token.
- Shared error codes: `AUTH_UNAUTHENTICATED` (401), `ACCESS_DENIED` (403), `NOT_FOUND` (404), `VALIDATION_FAILED` (400/422), `PRECONDITION_REQUIRED` (428), `PRECONDITION_FAILED` (412), `INTERNAL_ERROR` (500), `SERVICE_UNAVAILABLE` (503 with `Retry-After`: the database is unavailable or every connection is busy; retry with the same `Idempotency-Key`).

## Current user

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/me` | The caller's tenant, user ID, username, display name, principal type (`USER`/`CLIENT`), roles, branch scope (`allBranches`, `branchCodes`), clearance, the languages the tenant's screens offer (`languages`: `["en"]` or `["en", "ar"]`, ADR-0023) and request ID. Any authenticated caller |

## Documents

| Method | Path | Purpose | Action checked |
| --- | --- | --- | --- |
| POST | `/documents` | Multipart: `file` + `metadata` (JSON: cabinetCode, documentTypeCode, title, classification, branchCode, banking keys, customFields, folderId) | `UPLOAD` |
| GET | `/documents/{id}` | Metadata, status, current version, links | `VIEW_METADATA` |
| PATCH | `/documents/{id}` | Metadata change (may create a pending change) | `EDIT_METADATA` |
| DELETE | `/documents/{id}` | Soft delete (may require approval) | `DELETE` |
| POST | `/documents/{id}/restore` | Restore from recycle bin | `RESTORE` |
| GET | `/documents/{id}/versions` | Version list | `VIEW_METADATA` |
| POST | `/documents/{id}/versions` | Upload new version (when checked out by caller, or when type allows direct versioning) | `CHECK_OUT` |
| GET | `/documents/{id}/versions/{versionId}/content` | Original file (streamed) | `DOWNLOAD` |
| GET | `/documents/{id}/versions/{versionId}/preview` | PDF rendition, watermarked if classified | `VIEW_CONTENT` |
| GET | `/documents/{id}/versions/{versionId}/thumbnail` | Thumbnail | `VIEW_CONTENT` |
| POST | `/documents/{id}/checkout` · `/checkin` · `/cancel-checkout` | Locking | `CHECK_OUT` |
| GET | `/documents/{id}/history` | Approval records | `VIEW_METADATA` |
| GET | `/documents/{id}/audit` | Audit events for the document | `VIEW_AUDIT` |
| POST | `/documents/{id}/links` | Link documents | `EDIT_METADATA` |

### Documents as implemented in B-03

- `POST /documents`: `multipart/form-data` with a `metadata` part (JSON) **before** the `file` part; `Idempotency-Key`
  required (400 `IDEMPOTENCY_KEY_REQUIRED`; same key + same request → original response; same key + different request
  → 409 `IDEMPOTENCY_KEY_REUSED`). 201 with the document and `warnings` (`DUPLICATE_CONTENT` with a count, never IDs).
  Errors: 422 `VALIDATION_FAILED` with `errors` (unknown type, channel, classification, branch, folder, required
  standard fields, custom fields), 413 `FILE_TOO_LARGE` / `QUOTA_EXCEEDED`, 422 `FILE_EMPTY`.
- `PATCH /documents/{id}`: JSON merge patch (RFC 7396, `application/merge-patch+json` or JSON) with `If-Match`; 200 when
  applied, 202 with a pending change when the type requires approval for metadata changes (approved by a CHECKER).
- `POST /documents/{id}/versions`: draft (minor) version while checked out by the caller; multipart `comment` then
  `file`; `Idempotency-Key` required.
- `POST /documents/{id}/checkin`: multipart with optional `comment` and `file`, or JSON `{"comment": ...}`; without a
  file the latest draft becomes the next major version. `POST /documents/{id}/versions/{versionId}/restore`.
- Download: `application/octet-stream` attachment, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`. 500
  `STORAGE_INTEGRITY_FAILURE` (version marked `MISSING`), 409 `CONTENT_UNAVAILABLE`, 403 `DOCUMENT_QUARANTINED`.
- Interim access until B-04: roles (viewers read metadata but cannot download), plus clearance ≥ classification and
  branch scope; invisible documents are 404.

### Processing as implemented in B-06

- `GET /documents/{id}/versions/{versionId}/preview` (`VIEW_CONTENT`): the PDF rendition, or the original when the
  worker detected it as PDF, PNG, JPEG, GIF or WebP; inline, `Content-Security-Policy: sandbox; default-src 'none'`,
  `nosniff`, `no-store`; 409 `PREVIEW_UNAVAILABLE` before processing or when no preview is possible.
  `GET .../thumbnail`: PNG, 409 `THUMBNAIL_UNAVAILABLE`. Both 403 `DOCUMENT_QUARANTINED` for infected versions and
  audited (`DOCUMENT_PREVIEWED`, `DOCUMENT_THUMBNAIL_VIEWED`).
- Versions show `processingStatus`, `detectedMimeType` and `pageCount`.
- `GET /me/notifications` (own, newest first), `POST /me/notifications/{id}/read` (404 for others' notifications).
- `GET /admin/jobs?status=` and `POST /admin/jobs/{id}/retry` (`ADMIN_METADATA`, own tenant): dead or failed jobs
  get a fresh set of attempts; 409 `JOB_NOT_RETRYABLE` otherwise.

### Assisted indexing as implemented in B-15 (ADR-0024)

- `POST /captures` (multipart: `metadata` part `{documentTypeCode, branchCode, folderId?, classification?}`, then
  `file`; `Idempotency-Key` required): 202 `{id, status: PENDING, documentTypeCode, ocr, suggestions: [], expiresAt}`.
  Needs `UPLOAD` for the type, branch, folder and classification (403 `ACCESS_DENIED`); 422 for an unknown type,
  branch or folder or a classification outside the allowed range, before the file is stored; 413 `FILE_TOO_LARGE`
  above the cabinet's limit; 429 `CAPTURE_LIMIT` with five captures being read.
- `GET /captures/{id}` (the user who attached the file; `Cache-Control: no-store`): `status` `PENDING`, `DONE`,
  `INFECTED` or `FAILED`; once `DONE`, `suggestions` `[{field, value, confidence: HIGH|MEDIUM, label}]` where
  `field` is an upload field (`cif`, `customerName`, `accountNo`, `collateralCode`, `contractRef`,
  `transactionRef`, `applicationRef`, `expiryDate` or `customFields.<key>`) and `value` is in the upload API's form.
  404 `CAPTURE_NOT_FOUND` for anyone else, after expiry (one hour) or after `DELETE`; 403 when the owner no longer
  may upload there.
- `DELETE /captures/{id}` (owner): 204; the file and suggestions are deleted.

## Browse and search

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/cabinets` | Cabinets visible to the user |
| GET | `/cabinets/{code}/folders?parentId=` | Folder tree level |
| GET | `/documents?cabinetId=&type=&folderId=&cif=&account=&status=` | List with filters (newest first) |
| POST | `/search` | `{ "query": "...", "filters": {...}, "facets": ["documentType","branch"], "page": 0, "size": 20 }` → items with highlights (only with `VIEW_CONTENT`), facets, total |

### Browse as implemented in B-08

- `GET /cabinets` (active cabinets: `id`, `code`, `name`), `GET /cabinets/{id}/folders` (the whole tree, flat, parents
  first: `id`, `parentId`, `name`, `path`; 404 `CABINET_NOT_FOUND`), `GET /document-types?cabinetId=` (active types with
  `fields`; LOV fields carry their active `options` with English and Arabic labels). Any authenticated user of the
  tenant: this is configuration; documents are authorised one by one (ADR-0019).
- `GET /me/permissions`: `{ "actions": [...] }`, the caller's tenant-wide actions, for hiding controls.
- `GET /documents/{id}` adds `allowedActions` (`VIEW_CONTENT`, `DOWNLOAD`, `EDIT_METADATA`, `CHECK_OUT`, `DELETE`,
  `RESTORE`, `VIEW_AUDIT` as decided for the caller). Both are hints; every request is authorised again.

### Search as implemented in B-07

- `POST /search` (any authenticated user; results trimmed per caller, ADR-0015): `query` (≤ 500 chars, matched as
  plain words), `filters` (`documentType`, `cabinet`, `branch`, `status`, `classification`, `cif`, `accountNo`,
  `contractRef`, `collateralCode`, `transactionRef`, `applicationRef`, `externalRef`, `createdFrom`/`createdTo`,
  `expiryFrom`/`expiryTo`, `custom: {key: value}` for searchable custom fields), `facets` (`documentType`, `branch`,
  `status`, `cabinet`, `custom.<key>` for facet fields), `page` (default 0), `size` (1–100, default 20; up to 10,000
  results). Deleted and disposed documents are excluded unless `status` asks for them. Response: `items` (`id`,
  `title`, `documentTypeCode`, `cabinetCode`, `branchCode`, `status`, `classification`, `cif`, `accountNo`,
  `createdAt`, `snippets`: lists of `{text, match}` segments, only with `VIEW_CONTENT`), `facets` (`{value, count}`),
  `total`, `page`, `size`. 422 `VALIDATION_FAILED` (`SIZE_INVALID`, `PAGE_INVALID`, `QUERY_TOO_LONG`,
  `FACET_UNKNOWN`, `FIELD_NOT_SEARCHABLE`), 503 `SEARCH_UNAVAILABLE`. Audited (`SEARCH_PERFORMED`, without the query).
- `POST /admin/search/rebuild` (`ADMIN_METADATA`): 202 with `jobId` (`REBUILD_INDEX`, see `/admin/jobs`).

## Tasks (approvals)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/tasks?assignedTo=me` | Inbox, including candidate-group tasks, with SLA status |
| GET | `/tasks/{id}` | Task with subject summary and history |
| POST | `/tasks/{id}/claim` | Claim a group task |
| POST | `/tasks/{id}/complete` | `{ "decision": "APPROVE" \| "REJECT" \| "RETURN", "comment": "..." }` |

### Tasks as implemented in B-05

- Open to every authenticated user; each task is authorised individually (ADR-0013). `GET /tasks?assignedTo=me`
  (the only value) lists the tasks the caller can act on now: claimed by them or offered to one of their roles,
  visible to them, not their own item, not already decided by them in this round. Items: `id`, `name`, `kind`
  (`APPROVAL`, `VOTE`, `AMEND`), `processKey`, `stageKey`, `stageName`, `subjectType` (`DOCUMENT`, `VERSION`,
  `METADATA_CHANGE`, `ACCESS_RULE`, `ADMIN_CHANGE`), `subjectId`, `documentId`, `versionId`, `summary`, `round`,
  `makerId`, `assignee`, `createdAt`, `dueAt`, `slaStatus` (`NONE`, `ON_TIME`, `DUE_SOON`, `OVERDUE`), `decisions`
  (allowed values). `total` counts only those tasks.
- `GET /tasks/{id}`: the task and its item's approval history; for those who can act on it and for the maker.
- `POST /tasks/{id}/claim`; `POST /tasks/{id}/complete` with `decision` `APPROVE`, `REJECT`, `RETURN` (uploads and
  versions only) or `RESUBMIT` (the maker's amend task), `comment` (required for reject and return, ≤ 1000).
  Committee votes are `APPROVE`/`REJECT`. A pending change decided here is applied as with
  `/admin/pending-changes/{id}/approve` (409 `CHANGE_STALE` if it went stale).
- Errors: 404 `TASK_NOT_FOUND` (unknown, other tenant, not offered to the caller, or subject not visible), 403
  `TASK_MAKER_EXCLUDED`, 403 `ACCESS_DENIED`, 409 `TASK_ALREADY_CLAIMED`, 409 `TASK_ALREADY_DECIDED`, 409
  `TASK_CONFLICT` (concurrent decision, after retries), 422 `COMMENT_REQUIRED`, 422 `DECISION_NOT_ALLOWED`.
- Uploads and new major versions of a type with a `process_key` start the process in the same transaction; 409
  `APPROVAL_ROUTING_FAILED` or `COMMITTEE_QUORUM_UNREACHABLE` refuses the upload when no approver can be routed.
  While a checker decides, `PATCH /documents/{id}` is 409 `DOCUMENT_PENDING_APPROVAL`; a returned upload (`DRAFT`)
  is amended directly. `GET /documents/{id}/history` (`VIEW_METADATA`) lists the approval records, oldest first:
  `subjectType`, `subjectId`, `versionId`, `processKey`, `round`, `stageKey`, `stageName`, `actorId`, `actorName`,
  `decision`, `comment`, `decidedAt`.

## Retrieval requests

`POST /retrieval-requests`, `GET /retrieval-requests?mine=true`, `GET /retrieval-requests/{id}`,
`POST /retrieval-requests/{id}/issue`, `POST /retrieval-requests/{id}/return` (physical only).

## Records

`GET|POST /retention-policies`, `PUT /retention-policies/{id}`;
`GET|POST /legal-holds`, `POST /legal-holds/{id}/items` (document IDs or a saved search), `DELETE /legal-holds/{id}/items/{documentId}`, `POST /legal-holds/{id}/release`;
`GET /disposition-batches`, `POST /disposition-batches/{id}/approve`;
`POST /records/events` (e.g. `{ "type": "ACCOUNT_CLOSED", "accountNo": "...", "occurredAt": "..." }`) to start event-based retention.

As implemented in B-10 (ADR-0017), all under `/api/v1`: retention policies (`MANAGE_RETENTION`; POST/PUT are
proposals answered 202, approved by a second officer; body `code`, `name`, `triggerType`, `eventType`,
`periodMonths`, `dispositionAction`, `requiresApproval`, `documentTypeCodes`); legal holds (`MANAGE_HOLDS`): `POST
/legal-holds` `{name, reason}` → 201, `POST /legal-holds/{id}/items` `{documentIds}` and/or `{cif|accountNo|
contractRef}` (422 `DOCUMENT_UNKNOWN` for documents the officer cannot see), `DELETE .../items/{documentId}`,
`POST .../release` (409 `LEGAL_HOLD_RELEASED`); disposition (`DISPOSE`): `GET /disposition-batches[/{id}]`,
`POST /disposition-batches/prepare` (201 batch or 204), `POST /{id}/approve` (executes; items `DISPOSED`,
`SKIPPED` with a reason, or `FAILED`), `POST /{id}/reject` `{comment}` (409 `DISPOSITION_BATCH_NOT_PENDING` once
decided); `POST /records/events` (`MANAGE_RETENTION`) → 201. Documents: `DELETE /documents/{id}` → 200 (`DELETED`)
or 202 pending change; 409 `DOC_LEGAL_HOLD`, `DOC_DECLARED_RECORD`, `DOCUMENT_NOT_ACTIVE`, `DOCUMENT_CHECKED_OUT`;
`POST /documents/{id}/restore` (`RESTORE`) → 200, 409 `DOCUMENT_NOT_DELETED`; recycle bin `GET /documents?status=
DELETED`; content of disposed documents → 410. `DELETE /integration/v1/documents/{id}` behaves the same.

## Administration (all changes go through maker-checker)

Every write below is a proposal (ADR-0009): it validates the input (422 with `errors`, 409 `CODE_IN_USE` or
`CHANGE_PENDING`) and answers **202 Accepted** with the pending change and `Location: /admin/pending-changes/{id}`.
Nothing changes until a second admin with the approver role approves it. Updates and deletes need `If-Match` (the
object's `ETag`, i.e. its version). B-02 implements:

| Path | Methods | Role | Notes |
| --- | --- | --- | --- |
| `/admin/cabinets`, `/admin/cabinets/{id}` | GET, POST, PUT, DELETE (= deactivate) | TENANT_ADMIN | |
| `/admin/cabinets/{id}/folders?parentId=` | GET (one tree level), POST | TENANT_ADMIN | |
| `/admin/folders/{id}` | GET, PUT (rename/move), DELETE (empty folders) | TENANT_ADMIN | |
| `/admin/document-types?cabinetId=`, `/admin/document-types/{id}` | GET, POST (with initial `fields`), PUT, DELETE (= deactivate) | TENANT_ADMIN | |
| `/admin/document-types/{id}/fields` | GET, PUT (replace the ordered list; `If-Match` = type ETag) | TENANT_ADMIN | |
| `/admin/lovs`, `/admin/lovs/{id}` | GET, POST, PUT (values left out are deactivated), DELETE (unused only) | TENANT_ADMIN | |
| `/admin/languages` | GET (`languages`, `supported`, `defaultLanguage`; ETag), PUT `{ "languages": ["en", "ar"] }` | TENANT_ADMIN | ADR-0023; subject type `TENANT_LANGUAGES`; 422 `LANGUAGE_ENGLISH_REQUIRED`, `LANGUAGE_DEFAULT_REQUIRED`, `LANGUAGE_UNKNOWN` |
| `/admin/users/{id}/scope` | PUT | SECURITY_ADMIN | approver must not be the target user |
| `/admin/pending-changes?status=` | GET | TENANT_ADMIN, SECURITY_ADMIN | each sees the subject types their role approves; `canDecide` per item |
| `/admin/pending-changes/{id}` | GET | as above | |
| `/admin/pending-changes/{id}/approve` | POST `{ "comment": "..." }` | approver role, not the proposer | 200 applied; 409 `CHANGE_STALE` if the subject changed meanwhile (change becomes `STALE`); 409 `CHANGE_NOT_PENDING` |
| `/admin/pending-changes/{id}/reject` | POST `{ "comment": "..." }` (required) | approver role, not the proposer | |
| `/admin/pending-changes/{id}/cancel` | POST | proposer only | |

B-04 adds:

| Path | Methods | Who | Notes |
| --- | --- | --- | --- |
| `/admin/access-rules`, `/admin/access-rules/{id}` | GET, POST, PUT, DELETE | `ADMIN_ACCESS` | writes are proposals; approver must not be covered by the rule; 409 `LAST_ADMIN_ACCESS_RULE` |
| `/admin/delegations` | GET (own, or all for `ADMIN_ACCESS`), POST (delegate own roles, ≤ 90 days; 202) | any user | approved by a security admin who is neither party |
| `/admin/delegations/{id}/revoke` | POST | delegator, delegate or `ADMIN_ACCESS` | immediate |

B-05 adds `/admin/committees`, `/admin/committees/{id}` (GET, POST, PUT replacing the member list, DELETE =
deactivate; `ADMIN_METADATA`; writes are proposals approved by a second tenant admin): `code`, `name`, `minQuorum`
(1 to the number of members), `members` (`userId` of an active user of the tenant, `designation`), `status`.
Pending changes also appear as tasks in `/tasks` for the approver roles. B-17 adds
`GET /admin/committees/candidates` (`ADMIN_METADATA`): the tenant's active users `{id, username, displayName, roles}`
to name members, and `GET /branches` (any authenticated user of the tenant): `[{code, name}]`.

`/admin/pending-changes` is open to every authenticated user and lists the changes the caller may decide or has
proposed; deciding is authorised per change (403 `ACCESS_DENIED`, `CHANGE_APPROVER_EXCLUDED`). `GET /me` also returns
`delegatedRoles`. Authorization on documents: a document the caller may not view is 404 for every operation; other
denials are 403; all denials are audited as `ACCESS_DENIED` with the reason and deciding rule.

Later items add the rest of this section:

`/admin/cabinets`, `/admin/document-types`, `/admin/document-types/{id}/fields`, `/admin/lovs`,
`/admin/access-rules`, `/admin/committees`, `/admin/process-assignments`, `/admin/users/{id}/scope`
(branch scope, clearance), `/admin/delegations`, `GET /admin/pending-changes`.

Users (B-01; `SECURITY_ADMIN` only, same tenant; users of other tenants are 404):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/admin/users?page=&size=` | Users of the tenant with branch scope, clearance and `roles` (as mirrored from the token at their last sign-in; display only, B-17) |
| GET | `/admin/users/{id}` | One user; `ETag` = version |
| PUT | `/admin/users/{id}/scope` | `{ "allBranches": false, "branchCodes": ["001"], "clearance": "CONFIDENTIAL" }`; requires `If-Match` (428 without, 412 if stale); `USER_SELF_CHANGE` (403) for your own user; `BRANCH_UNKNOWN` (422). Direct in B-01; becomes a pending change (maker-checker) in B-02 |

## Audit

`GET /audit?resourceType=&resourceId=&actorId=&action=&outcome=&from=&to=&page=&size=` (`VIEW_AUDIT`; the query
itself is audited as `AUDIT_QUERIED`): newest first; items carry `id`, `seq` (chain position), `occurredAt`,
`actorType`, `actorId`, `action`, `resourceType`, `resourceId`, `outcome`, `clientIp`, `requestId`, `details`,
`prevHash`, `hash`. `from` is inclusive and `to` exclusive (ISO-8601 instants); `from` not before `to` → 422
`VALIDATION_FAILED`.
`GET /audit/verification` (latest hash-chain verification result; 404 `AUDIT_VERIFICATION_NOT_FOUND` before the
first run), `POST /audit/verification` (verify now; also runs daily): `status` (`VALID`, `BROKEN`), `eventsChecked`,
`headSeq`, `headHash`, and for a broken chain `brokenSeq`, `brokenEventId`, `failure` (`HASH_MISMATCH`,
`LINK_BROKEN`, `SEQUENCE_GAP`, `HEAD_MISMATCH`). Both `VIEW_AUDIT` (ADR-0018).
`GET /documents/{id}/audit`: the document's events, oldest first, at most 1000 (`VIEW_AUDIT` on the document;
`VIEW_METADATA` not needed; neither → 404). Opening a document (`GET /documents/{id}`) is audited as `DOCUMENT_VIEWED`.

## Integration API (bank systems)

Base `/integration/v1`. Same auth and error model; stricter rate limits; all calls audited with the client ID.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/documents` | Multipart upload with business keys: `externalSystem`, `documentTypeCode`, `category`, `cif`, `customerNo`, `accountNo`, `collateralCode`, `contractRef`, `transactionRef`, `applicationRef`, `issueDate`, `expiryDate`, `customFields`; returns `documentId`, `status` |
| GET | `/documents?cif=&accountNo=&contractRef=&applicationRef=&documentTypeCode=` | List by business keys |
| GET | `/documents/{id}` | Metadata |
| GET | `/documents/{id}/content` | Current approved version |
| GET | `/documents/{id}/preview` | PDF rendition (e.g. for signature/image display in FLEXCUBE screens) |
| POST | `/documents/{id}/versions` | New version |
| DELETE | `/documents/{id}` | Soft delete (approval rules apply) |

Legacy-to-new mapping for existing callers is in `06-legacy-jdms-reference.md` §4.

As implemented in B-09 (ADR-0016): OAuth2 clients only (403 for users). `POST /documents`: multipart `metadata`
(`externalSystem` required, `documentTypeCode`, optional `cabinetCode`, `title` (default: type and main business
key), `classification`, `branchCode`, `folderId`, `cif` or `customerNo`, `customerName`, `accountNo`,
`collateralCode`, `contractRef`, `transactionRef`, `applicationRef`, `externalRef`, `issueDate`, `expiryDate`,
`customFields`) then `file`; `Idempotency-Key` required; 201 `{documentId, versionId, status, warnings}`; 422
`FIELD_NOT_ALLOWED` for `user_id`/`role_id`, `EXTERNAL_SYSTEM_INVALID`, `CUSTOMER_MISMATCH` and the usual metadata
violations. `GET /documents` needs at least one of `cif`, `accountNo`, `contractRef`, `applicationRef` (422
`BUSINESS_KEY_REQUIRED`). `GET /documents/{id}/content` and `/preview` serve the current version.
`POST /documents/{id}/versions` (multipart `comment`, `file`; `Idempotency-Key`): a new major version in one call.
B-18 (ADR-0025): `folderPath` on upload (folder names in the type's cabinet; 422 `FOLDER_INVALID`), `externalRef` as a
lookup key of `GET /documents`. The vendor-facing description is `docs/api/jdms-integration-v1.openapi.yaml`.
Every call: 429 `RATE_LIMITED` with `Retry-After` beyond the client's rate; audited as `INTEGRATION_CALL`. 503
`STORAGE_UNAVAILABLE` when the object store is down. `DELETE` arrives with B-10.

### Legacy migration as implemented in B-12

Base `/integration/v1/migration`, clients holding `IMPORT` (role `MIGRATION`) only (ADR-0020); rate-limited and audited
like every integration call.

- `POST /documents`: multipart, a `metadata` part then one `file` part per version, oldest first. Metadata:
  `legacyDocumentId`, `status` (`ACTIVE` or `DELETED`), `createdAt`, `createdBy`, the upload fields
  (`documentTypeCode`, `title`, `classification`, `branchCode`, `folderId`, banking keys, `expiryDate`,
  `customFields`), `versions` (`fileName`, `mimeType`, `sha256` of the decrypted file, `createdAt`, `createdBy`,
  `comment`), `history` (`round`, `stage`, `actor`, `decision`: `SUBMIT`/`APPROVE`/`REJECT`/`RETURN`, `comment`,
  `decidedAt`), `permissions` (`role`, `legacyRole`, `actions` among `VIEW_METADATA`, `VIEW_CONTENT`, `DOWNLOAD`,
  `EDIT_METADATA`, `CHECK_OUT`, `DELETE`). 201 with `documentId`, `status`, `versions` (`number`, `versionId`,
  `sha256`, `sizeBytes`), `historyRecords`, `rules`; 200 with `alreadyImported: true` when the legacy ID was imported
  before (files not read). 422 `VALIDATION_FAILED` (as an upload, plus `LEGACY_ID_REQUIRED`, `STATUS_INVALID`,
  `VERSIONS_INVALID`, `SHA256_INVALID`, `HISTORY_INVALID`, `PERMISSION_INVALID`), 422 `CHECKSUM_MISMATCH`,
  422 `VERSION_FILES_MISMATCH`, 403 without `IMPORT`.
- `POST /access-check` `{ "roles": [...], "allBranches": false, "branchCodes": [...], "clearance": "...",
  "documentIds": [...] }` → `{ "<documentId>": ["VIEW_METADATA", ...] }` for the hypothetical user; other tenants'
  documents are left out; not audited per decision.

## Events (outbox → internal listeners and jobs)

`DocumentCreated`, `VersionCreated`, `MetadataChanged`, `DocumentApproved`, `DocumentRejected`, `DocumentDeleted`,
`DocumentRestored`, `AccessRulesChanged`, `LegalHoldChanged`, `RecordDeclared`, `DocumentDisposed`.
Payloads are records in `jdms-contracts`, carrying IDs and tenant only — never content or sensitive metadata.
Webhooks for external subscribers are a later phase.
