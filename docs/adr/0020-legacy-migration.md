# 0020 — Legacy migration: import API in the core, decryption only in the tool, reconciliation per run

- Status: Accepted
- Date: 2026-10-25

## Context

B-12 moves a bank's legacy JDMS data (Oracle tables plus AES-encrypted files on disk, `06-legacy-jdms-reference.md`
§2 and §5) into JDMS Next. The legacy key may only be used inside `jdms-migration` (CLAUDE.md rule 11); JDMS Next
keeps its own envelope encryption, approval rules, audit and tenant isolation; nothing may be skipped silently.

## Decision

**Split.** `jdms-migration` reads the legacy database (read-only JDBC, bound parameters, SQL in one adapter class)
and the legacy file tree, decrypts files in one class (`LegacyFileCipher`), maps rows to the new model and sends
them to the core over HTTP as an OAuth2 client holding the `MIGRATION` role. The core never sees the legacy key; the
tool never sees a JDMS Next key or table. Content travels decrypted only inside this TLS connection, and is
re-encrypted by the core's normal `ContentWriter` (envelope encryption with the tenant key).

**Legacy decryption.** AES-128 in ECB mode with PKCS#5 padding, key = the first 16 bytes of SHA-1 of the legacy
secret, as the legacy code does (reference §5; to be confirmed against a real sample). The secret is read at runtime
from a file (`JDMS_LEGACY_SECRET_FILE`, e.g. a mounted Kubernetes or Vault secret), held only inside the cipher and
never logged, printed or put in exceptions; a test asserts the logs contain neither the secret nor the derived key.
A file that fails to decrypt (bad padding) or is missing is an exception in the report, never a silent skip.

**Import API (core, `/integration/v1/migration`, clients with `IMPORT`).**
- `POST /documents`: multipart, a `metadata` part (JSON) then one `file` part per version, oldest first. The core
  validates the metadata exactly like an upload (type, branch, folder, classification, required and custom fields),
  stores every version as a new encrypted blob, checks each part against the SHA-256 the tool computed from the
  decrypted file (`CHECKSUM_MISMATCH` otherwise), and in one transaction creates the document (`source_system =
  MIGRATION`, `legacy_document_id`, the legacy creation time and user), its versions (approved; the newest is
  current), the approval history (`approval_record` rows with the legacy actors, decisions and times, process
  `legacy-import`), the legacy per-document role permissions as document-scope access rules, and a
  `DOCUMENT_IMPORTED` audit event. Deleted legacy documents arrive in the recycle bin. Every version is queued for
  the normal processing pipeline (antivirus, extraction, renditions) and the document is indexed: legacy files are
  as untrusted as new ones. Idempotent by `legacy_document_id`: a document already imported returns 200 with what
  was stored (`alreadyImported`), and its file parts are not read.
- `POST /access-check`: the actions a hypothetical user (roles, branches, clearance) would have on given documents,
  decided by the access policy without audit, for the reconciliation's permission comparison.

Per-document legacy permissions become rules without a checker because they reproduce access that was already
approved in the legacy system, the import itself is a controlled, audited run by a dedicated client, and the
reconciliation report compares effective permissions user by user. Everything that is new configuration goes
through maker-checker: document types and fields, folders and folder-level permissions are proposed by the tool
through the existing administration API (`--catalog`) and approved by the bank's administrators before documents
are imported.

**Mapping** (reference §5): `DMS_DOCUMENT_UPLOAD` → document; status from `status`/`wf_status` (deleted → recycle
bin); `DMS_CUSTOM_DOCFIELD_DTS` slots → custom fields through `DOC_FIELD_DETAILS.field_id`; files under the base
folder are the current version, numbered older files under the version folder are earlier versions, files under the
delete folder belong to deleted documents; `WF_WORKFLOWHISTORY` → approval records; `doc_permission` and `role_id`
→ document rules through a configured legacy-role → JDMS-role map; `DOC_TYPE_MASTER` and `DOC_FIELD_DETAILS` →
document type proposals; `CM_HISTORY` is not imported into the new hash chain (exported for evidence separately).

**Runs and reconciliation.** Every run writes `reconciliation.json` and `reconciliation.md`: counts per cabinet and
type (legacy, migrated, already migrated, failed), SHA-256 of each decrypted file against the core's, field-by-field
metadata differences (what the core stored, read back through the integration API, against the legacy values),
version and approval-history counts, a permission comparison for sampled users, and the exceptions (corrupt, missing,
duplicate, undecryptable, invalid metadata) with their reasons. Every legacy document ends in exactly one outcome.
Re-runs are idempotent; `--since=<instant>` (delta mode) takes legacy documents created or changed after the cut-off:
new ones are imported, already imported ones that changed are reported as `CHANGED_AFTER_MIGRATION` for review.

## Consequences

- The legacy file layout (version and delete folders, version numbering) and the legacy permission codes are
  configuration, confirmed per bank against samples (O-03 style); the PoC fixture follows reference §2.
- The core test fixture becomes a test-jar and the core jar is the plain one (the executables of the core and the tool
  are classified `exec`). The end-to-end test lives in a test-only module, `jdms-migration-e2e`, which runs the real
  core in-process; the tool's own tests stay on its production classpath (the core on that classpath would otherwise
  pull JPA, Flyway and Flowable into the tool's context).
- In-flight legacy approvals are not migrated as running processes: they should be finished in JDMS before cut-over
  (reference §5); the tool reports documents still in workflow as exceptions.
