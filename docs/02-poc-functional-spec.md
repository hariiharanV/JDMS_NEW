# 02 — PoC functional specification

## 1. Purpose

The PoC proves that the JDMS Next core can replace the legacy JDMS for a FLEXCUBE bank: secure storage,
versioning, metadata, maker-checker approvals, permission-trimmed search with OCR, the FLEXCUBE integration
API, records controls and migration of legacy encrypted documents. Its exit criteria are the Phase 0
checklist (section 9).

## 2. Scope

**In scope**

- Tenants (banks), branches, users and roles via Keycloak
- Cabinets, document types, custom field definitions and lists of values (LOVs)
- Upload with metadata, versions, check-out/check-in, download, browser preview
- Virtual folders for browsing
- Maker-checker approval: single stage, multi-stage, committee with quorum; reject and return
- Authorization model (section 6) on every access path
- Processing pipeline: antivirus, text extraction, PDF rendition, thumbnail, OCR
- Full-text search with filters and facets, permission-trimmed
- Integration API for FLEXCUBE and other bank systems
- Records basics: retention policy, legal hold, soft delete (recycle bin) and restore, disposition with approval
- Physical and digital retrieval requests with approval
- Audit trail with hash chain and verification
- Migration tool for legacy JDMS documents and metadata
- Admin screens for the metadata model, access rules, workflows (assignment of process to document type), retention and holds

**Out of scope for the PoC** (later phases): AI/RAG, WOPI co-editing, e-signature and certificate
generation, bulk email/SMS, screen builder, dashboards and graphs, scheduled report generation,
backup/recovery screens (replaced by platform backup plus recycle bin), Oracle database, multi-bank shared hosting.

## 3. Actors

| Actor | Description |
| --- | --- |
| Maker | Branch or operations user who uploads and indexes documents |
| Checker | Approves or rejects documents, metadata changes and deletions |
| Committee member | Votes on items routed to a committee stage |
| Viewer | Searches and views documents within their scope |
| Records officer | Manages retention policies, legal holds and disposition |
| Tenant admin | Manages cabinets, document types, fields, LOVs, access rules and workflow assignment |
| Security admin | Manages roles and access rules; cannot approve their own changes |
| Integration client | A bank system (FLEXCUBE, LOS, CRM) calling the integration API with its own OAuth2 client |

## 4. Core behaviour

### 4.1 Metadata model administration

- A tenant has cabinets (legacy "application"/`appln_id`, e.g. CUSTOMERS, LOANS). A cabinet has a code, name, quotas (max file size, total size) and optional default workflow.
- A document type belongs to a cabinet: code, name, category, channel flag (which channels may upload), default classification, retention policy, approval process, and an ordered list of field definitions.
- Field definition: key, labels (en, ar), type (`TEXT`, `NUMBER`, `DATE`, `BOOLEAN`, `LOV`), mandatory, max length, default value, LOV code, searchable flag, facet flag, sensitive flag (masked in logs and exports), display order and row.
- Standard banking fields exist on every document and are searchable: CIF/customer number, customer name, account number, collateral code, contract reference, transaction reference, application reference, branch code, expiry date, physical location, physical reference.
- Admin changes to cabinets, types, fields and access rules go through maker-checker (a second admin approves).

### 4.2 Upload and indexing

1. The user picks a cabinet and document type; the UI renders the type's fields as a form.
2. The user attaches one or more files (max size from cabinet settings; default 100 MB in the PoC).
3. The core validates metadata (mandatory fields, types, LOV values), stores the file as a new blob with SHA-256, creates the document and version 1.0 with `storage_status = STORED`, `processing_status = PENDING`, and writes an audit event and an outbox event.
4. If the document type has an approval process, the document is `PENDING_APPROVAL` and a task is created; otherwise it is `ACTIVE`.
5. The worker scans, extracts, renders and OCRs asynchronously, then requests indexing. An infected file sets `processing_status = QUARANTINED`, blocks download for everyone except security admins and notifies the uploader.
6. Duplicate detection: if a file with the same SHA-256 already exists in the same cabinet for the same CIF, the user is warned (not blocked).

### 4.3 Versions and check-out

- Versions are immutable. Numbering: major.minor. Approved content is a major version; drafts uploaded while checked out are minor versions.
- Check-out locks the document for others (they can still read). Check-in creates a new version and, if the type requires approval, puts the version into approval; the previous approved version stays current until approval.
- Cancel check-out discards draft versions not yet checked in.
- Any version can be downloaded by users allowed to view it; restoring an old version creates a new version (history is never rewritten).
- Metadata changes are versioned in the audit trail; if the type requires approval for metadata changes, the change is pending until approved.

### 4.4 Approval (maker-checker and committees)

- Each document type maps to a Flowable process. The PoC ships three reusable processes:
  - `single-approval`: one checker role approves or rejects
  - `multi-stage-approval`: ordered stages, each with a role or named user; routing rules in DMN (e.g. by amount, document category or branch)
  - `committee-approval`: a committee with members and a minimum quorum; approved when approvals reach the quorum, rejected when approval can no longer reach it
- Actions on a task: `APPROVE`, `REJECT` (ends the process; document `REJECTED`), `RETURN` (back to the maker with a comment; maker can amend and resubmit). Comments are mandatory for reject and return.
- Separation of duties: the maker can never approve their own item, at any stage; a user cannot vote twice in a committee.
- Every action records an approval history entry (who, when, decision, comment, stage) visible on the document.
- SLA per stage (hours); overdue tasks are flagged in the inbox. Escalation notification is out of scope.
- If a step fails mid-way (e.g. DB or search outage), the process resumes from the last committed state; no duplicate approvals.

### 4.5 Browse, view and download

- Browse by cabinet, then virtual folders, document type or customer (CIF).
- Viewer: PDF.js for PDF renditions; images inline; other formats via their PDF rendition. Watermark previews and downloads with user ID, time and classification (for `CONFIDENTIAL` and above).
- Download of the original requires the `DOWNLOAD` action; view-only users get the preview only.

### 4.6 Search

- Full-text over extracted and OCR text plus metadata; filters on standard fields, document type, date ranges, status; facets on document type, branch, status and facet-flagged custom fields.
- Results, snippets, facet counts and totals include only documents the user may view (section 6). Snippets are not shown for documents where the user lacks `VIEW_CONTENT`.
- New documents are searchable by metadata immediately after commit and by content once processing completes.

### 4.7 Delete, recycle bin and restore

- Delete is soft: the document moves to the recycle bin (`DELETED`), subject to approval if the type requires it. Restore returns it to its previous status.
- Documents under legal hold or declared as records cannot be deleted.
- Permanent removal happens only through disposition (4.8).

### 4.8 Records

- Retention policy: code, trigger (`CREATION_DATE`, `EXPIRY_DATE`, `EVENT` such as account closure received via API), period, disposition action (`DESTROY`, `REVIEW`).
- Declaring a record (automatic on approval for types configured as records) sets the blob's object-lock retention when the store supports it.
- Legal hold: named hold with reason; documents added by search or explicit selection; blocks delete and disposition regardless of retention; only records officers create or release holds.
- Disposition: a daily job lists eligible documents; a records officer approves a disposition batch; approved items have blobs destroyed and metadata reduced to a tombstone (ID, type, dates, disposition approval reference) kept for evidence.

### 4.9 Retrieval requests

- A user without access (or needing a physical file) raises a request: document or CIF, purpose, type `DIGITAL` or `PHYSICAL`, needed-by date.
- Approval through `single-approval`. On approval of a digital request, a time-bound document grant is created (default 7 days). Physical requests record issue and return dates.

### 4.10 Integration API

- Bank systems upload, list, fetch metadata and download documents by business keys (CIF, account, contract, application reference) using OAuth2 client credentials. The client's service account has roles like any user; there is no impersonation by sending a user ID.
- Idempotency key required on uploads; same key and same payload return the original result.
- The legacy request fields (section 4 of `06-legacy-jdms-reference.md`) map onto the new request so that existing callers can be adapted with minimal change. A thin compatibility adapter for the legacy XML/JSON contract is a stretch goal, behind a feature flag.

### 4.11 Audit

- Events: login (from Keycloak), view, preview, download, upload, version, metadata change, approve/reject/return, delete/restore, permission change, hold/retention change, disposition, search (query terms hashed), admin changes, integration calls.
- Each event: ID, tenant, time (UTC), actor (user or client), action, resource type and ID, outcome, client IP, request ID, details (JSON, no content), `prev_hash`, `hash`.
- Hash chain per tenant; a verification job reports any break. Audit query screen for admins with filters; reading the audit trail is itself audited.

## 5. Statuses

| Field | Values |
| --- | --- |
| `document.status` | `DRAFT`, `PENDING_APPROVAL`, `ACTIVE`, `REJECTED`, `DELETED`, `DISPOSED` |
| `version.approval_status` | `DRAFT`, `PENDING`, `APPROVED`, `REJECTED` |
| `version.storage_status` | `STORED`, `MISSING`, `QUARANTINED`, `DESTROYED` |
| `version.processing_status` | `PENDING`, `PROCESSING`, `DONE`, `FAILED`, `QUARANTINED` |
| `version.index_status` | `PENDING`, `INDEXED`, `FAILED` |
| `retrieval_request.status` | `PENDING`, `APPROVED`, `REJECTED`, `ISSUED`, `RETURNED`, `EXPIRED` |

## 6. Authorization model (PoC implementation)

Evaluation order; the first denial ends evaluation; nothing allowed means denied:

1. Tenant boundary (no override)
2. Records state: legal hold or declared record blocks `EDIT`, `DELETE`, `DISPOSE`
3. Explicit deny rule on user, role or document
4. Classification: user clearance ≥ document classification
5. Role permission on the document type (actions below)
6. Scope attributes: user's branch scope covers the document's branch (`ALL` or a list of branches)
7. Document grants: time-bound grants (from retrieval requests or shares) for named users

Actions: `VIEW_METADATA`, `VIEW_CONTENT`, `DOWNLOAD`, `UPLOAD`, `EDIT_METADATA`, `CHECK_OUT`, `DELETE`,
`RESTORE`, `APPROVE`, `MANAGE_HOLDS`, `MANAGE_RETENTION`, `DISPOSE`, `ADMIN_METADATA`, `ADMIN_ACCESS`, `VIEW_AUDIT`.

Rules: inheritance (cabinet → type → document; document-level rules may restrict freely and widen only with
approval); delegation (time-bound, never more than the delegator holds, no re-delegation); separation of duties
(maker ≠ checker; nobody approves their own permission change); revocation effective within 60 seconds
(cache TTL ≤ 60 s plus event-driven invalidation; search filter built per request from current rules).

Interface (in `access/api`):

```java
Decision decide(Subject subject, Action action, ResourceRef resource);
SearchFilter searchFilter(Subject subject, Action action); // translated to an OpenSearch filter
```

Every `Decision` carries the rule that decided it, for audit and tests. The authorization test matrix covers
each actor × action × (own branch, other branch, other tenant, above clearance, held document).

## 7. Non-functional targets for PoC tests (proposed, confirm via O-06)

| Measure | Target |
| --- | --- |
| Search response p95 | < 1.5 s on 1 million test documents |
| Metadata searchable after upload | immediately after commit |
| Content searchable (digital) p95 | < 2 min; scans with OCR < 10 min |
| First-page preview p95 | < 2 s |
| Bulk ingestion | 100,000 documents/hour (worker scaled out) |
| Permission revocation | effective within 60 s on every path |
| Max file size | 500 MB configurable (PoC default 100 MB) |

## 8. UI screens (PoC)

Login (Keycloak), Home (my tasks, recent documents), Browse (cabinet → folder/type/customer), Upload (dynamic
form), Document detail (metadata, versions, approval history, audit, links, viewer), Task inbox, Search (filters,
facets, results), Retrieval requests, Recycle bin, Records (policies, holds, disposition batches), Admin
(cabinets, document types and fields, LOVs, access rules, workflow assignment, committees), Audit query.
Browse uses a three-panel layout: folder tree, then document list or viewer, then properties (requested in bank RFPs).
All screens responsive, keyboard accessible, English and Arabic (RTL). Arabic is offered per bank and Arabic names are optional (ADR-0023).

## 9. PoC exit checklist (Gate 1)

- [ ] Build vs Alfresco vs one commercial platform compared on cost, capability and ownership (outside this repo)
- [ ] Upload, immutable version history, secure retrieval and metadata management demonstrated
- [ ] Maker-checker and committee workflows demonstrated, including recovery after a failed operation
- [ ] FLEXCUBE integration demonstrated with a representative interface and failure scenarios
- [ ] Permission enforcement proven across search, snippets, facets, preview, download and export
- [ ] Representative encrypted legacy documents migrated with metadata, version and permission reconciliation
- [ ] Object-store retention, key recovery, backup restore and disaster recovery tested
- [ ] OCR and extraction benchmarked on representative banking documents
- [ ] Performance, availability, RPO/RTO and migration targets agreed and measured
- [ ] Refined estimate, staffing plan, licence inventory (SBOM) and threat model produced
