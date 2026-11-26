# 0015 — Search index and query-time access filtering

- Status: Accepted
- Date: 2026-10-25

## Context

Spec §4.6 and §6: full-text search over metadata and extracted/OCR text, permission-trimmed in results, totals,
facet counts and snippets; snippets only with `VIEW_CONTENT`; revocation within 60 seconds. The domain model sketched
ACL fields per document (`acl_allow_roles`…) that would need re-indexing on every rule or grant change.

## Decision

- **Query-time access, not indexed ACLs.** The index holds each document's access-relevant attributes (cabinet,
  type, folder, branch, classification, document ID); every search builds its filter from
  `AccessPolicy.searchFilter(...)` with the *current* rules and grants — the same translation the database list uses
  (B-04), folder scopes expanded to the folder's current subtree. Rule changes, grants, grant expiry and folder moves
  therefore need no re-index; the stale window is the rule cache TTL (≤ 60 s, 30 s by default, evicted at once on
  this node by `AccessRulesChanged`). Legal holds do not affect viewing. Changes of a document's own attributes
  (branch, classification, folder, type) are metadata changes and re-index that document. The `acl_*` fields of the
  domain model are not used.
- **Content only where it may be read.** The free-text query is `metadata OR (content AND VIEW_CONTENT filter)`
  inside the `VIEW_METADATA` filter: totals and facets count only viewable documents, and matching text never
  surfaces a document to someone who may see only its metadata. Every hit is re-checked with the policy against the
  database before it is returned (a disagreement is logged and the hit dropped), and snippets are removed unless the
  caller holds `VIEW_CONTENT`. Snippets are returned as segments (`text`, `match`), never HTML.
- **Index.** One alias per tenant (`jdms-<tenant>-documents`) in front of a concrete index (first `…-v1`, rebuilds
  timestamped); one entry per document (current version). Mapping in `search/document-index.json`: keyword ids and
  codes; `title`, `customer_name` and `content` with English and Arabic analyser sub-fields; custom fields typed by
  their definition (`custom_text|keyword|number|date|bool.<key>`), only searchable or facet fields and never
  sensitive ones. Searches use `multi_match` values only (no query-string syntax).
- **Indexing in the core.** After every committed change (`DocumentCreated`, `VersionCreated`, `MetadataChanged`,
  `DocumentChanged` for approval outcomes and processing results) the document is indexed at once (`refresh=
  wait_for`); a failure becomes an `INDEX` job. Writes are whole-document upserts with an external version that grows
  with the document, its current version and its processing state (`external_gte`), so repeated deliveries do not
  duplicate and late ones do not roll back. Writes require the alias, so a removed index is recreated with its
  mapping, never auto-created without it. The core reads the worker's TEXT rendition (plain text it never parses)
  and indexes at most 1 M characters, without control characters and bidi marks (OCR wraps Arabic in them, which
  would otherwise stick to the words). `index_status` becomes `INDEXED` once the version's final state is indexed.
- **Rebuild** (`POST /admin/search/rebuild`, `REBUILD_INDEX` job): every document into a new index, alias switched
  atomically, then a second pass through the alias so changes made during the first pass are kept.
- **Client.** OpenSearch's REST API over the JDK HTTP client with Jackson (no client library); errors report status
  and error type only, never response bodies (they can echo indexed text).

## Consequences

- Search latency includes the policy re-check of one page (≤ 100 documents); B-13 measures p95 at volume.
- If rules and the index disagree (index lag), a hit can be dropped after totals were counted; the window is bounded
  by indexing after commit and retries.
