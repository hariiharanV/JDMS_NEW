# 0011 — Content storage, encryption and streaming upload

- Status: Accepted
- Date: 2026-10-24

## Context

B-03 requires envelope encryption (CLAUDE.md rule 7), SHA-256 verified on read, corrupt content detected, size limits
per cabinet, and no file names as identifiers. Files can be up to 500 MB, and the legacy system's defects include
decrypted temp files on disk.

## Decision

- **Encryption.** Per object a fresh 256-bit data key, wrapped with AES-256-GCM by a per-tenant key (`KeyService`).
  Content is encrypted with **Tink streaming AES-GCM-HKDF** (Apache-2.0; 1 MiB segments): every segment is
  authenticated before its bytes are released and truncation is detected, without holding the file in memory. The
  blob key is the associated data, so ciphertext moved to another key fails to decrypt.
- **Local KMS stub.** The tenant key is HKDF-SHA256(master key, "jdms-tenant-key:" + tenant ID). The master key comes
  only from `JDMS_LOCAL_KMS_MASTER_KEY`; startup fails without it (tests use an ephemeral key). `key_ref` = `local:v1`
  so a real KMS or key rotation can be added behind `KeyService`.
- **Streaming upload.** Spring's multipart handling is disabled; uploads are parsed with **Apache Commons FileUpload 2
  streaming API**, so the file part flows straight into hashing, size counting, encryption and an S3 multipart upload
  (8 MiB parts). No plaintext touches disk; memory per upload is one part. The `metadata` part must come before the
  `file` part, so metadata is validated before any byte is stored. FileUpload 2 is still a **milestone (2.0.0-M5)**:
  pinned, isolated in `StreamingMultipart`, to be moved to 2.0.0 GA when released (fallback: servlet `Part` API).
- **Download.** Always `application/octet-stream` attachment with `nosniff` and `no-store`. The first segment is
  decrypted before headers are sent and the last chunk is held back until the SHA-256 matches, so a client never gets a
  complete but wrong file. Failures mark the version `MISSING`, write an audit event, log an `ALERT` line and count
  `jdms.storage.integrity.failures`.
- **Blob keys.** `<tenant_code>/<yyyy>/<mm>/<2 hex>/<uuid>`. The domain model asks for the first two hex characters of
  the content SHA-256; that is only known after streaming, which would force a copy to a new key. The two characters
  are taken from SHA-256 of the blob UUID instead: same spreading effect, known up front, still no business data.
- **Consistency.** Blob first, then one database transaction (document, version, audit, outbox, idempotency record).
  On failure the blob is deleted; anything left behind is found by `OrphanBlobReconciler` (daily, 1 h grace; reports,
  deletes only when `jdms.storage.reconcile.delete-orphans=true`).
- **Immutability.** Version content columns are `updatable = false` and a database trigger rejects changes to them and
  deletes of non-draft versions.
- **Licences.** `org.reactivestreams:reactive-streams` (required by the AWS SDK) is MIT-0, i.e. MIT without the
  attribution clause; allowed as part of the MIT family (`backend/build/README.md`).

## Consequences

- The worker (B-06) needs the same decryption and `KeyService`: the crypto classes move to a shared library module
  then (ADR at B-06).
- Production replaces the local KMS stub with a real KMS/HSM behind `KeyService`.
