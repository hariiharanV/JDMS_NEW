# 0014 — Processing pipeline and the shared platform module

- Status: Accepted
- Date: 2026-10-25

## Context

B-06 moves untrusted-file work into `jdms-worker` (CLAUDE.md rule 6): antivirus, type detection, text extraction,
OCR in English and Arabic, PDF renditions and thumbnails. The worker must read envelope-encrypted originals and
write encrypted renditions (ADR-0011), and talk to the core only through the database (no Kafka, D-11). Every state
change needs a hash-chained audit event (rule 10), which only the core writes.

## Decision

- **New Maven module `jdms-platform`** (a library, no Spring Boot auto-configuration, no domain logic) shared by
  core and worker: blob storage over S3 (`S3BlobStore`), the local KMS stub (`KeyService`), envelope encryption
  (`EnvelopeEncryption`, moved unchanged from the core), UUIDv7 IDs, and the job queue (`JobStore` with a PostgreSQL
  adapter using `FOR UPDATE SKIP LOCKED`, and `JobRunner`). The core keeps its domain-facing `ContentStore` adapter.
- **The worker is a stateless processor**: it reads `tenant` and `document_version` (read-only, tenant in every
  query), reads originals, stores renditions as new blobs and reports each step's outcome as a
  `PROCESSING_RESULT` job (`jdms-contracts`: `ProcessingResult`) in the same transaction that completes the step.
  **The core applies results** (job runner in the core): processing status, detected type, page count, `rendition`
  rows, audit events, and the `VersionQuarantined` event that notifies the uploader. One writer for domain tables and
  the audit chain; the worker needs no domain logic.
- **Pipeline**: SCAN (ClamAV INSTREAM) → EXTRACT (Tika detects the type from the bytes; text with OCR skipped) →
  OCR (only images and PDFs with fewer than 20 letters/digits of text; Tesseract `eng+ara` through Tika; PDF pages
  rendered at 150 dpi) → RENDER (Gotenberg/LibreOffice PDF for office and text files; PNG thumbnail of page 1 with
  PDFBox, or of the image, subsampled). Each step is its own job: retried with exponential backoff, `FAILED` at once
  for permanent errors (Tika 422, Gotenberg 4xx, too large), `DEAD` after `max_attempts`; both report a `FAILED`
  result so the version does not stay in processing. `/admin/jobs` lists and retries dead jobs.
- **Limits** (`jdms.worker.*`): input size (100 MB, matching clamd's `StreamMaxLength`), per-call timeouts, extracted
  text (5 M chars, cut), PDF rendition size, thumbnail pixels/size/timeout (best effort: no thumbnail rather than a
  failure). Parsers run in other processes (Tika server with its watchdog, Gotenberg, clamd); only thumbnails are
  rendered in the worker JVM, from capped input with subsampling and a timeout.
- **Idempotency**: results are applied under a row lock on the version; one rendition per kind (unique constraint);
  a rendition produced again by a duplicate delivery is discarded and its blob deleted after commit; nothing is kept
  for a quarantined version; the worker skips steps whose renditions the core already holds and completed versions.
  The orphan reconciler treats rendition blobs as referenced.
- **Preview** (`/preview`, `VIEW_CONTENT`): the PDF rendition; or the original when the worker *detected* it as PDF,
  PNG, JPEG, GIF or WebP (never the uploader's claimed type; never SVG). Served inline with
  `Content-Security-Policy: sandbox`, `nosniff`, `no-store`. `/thumbnail` serves the PNG. Quarantined versions are
  blocked for previews too.
- **Tika image**: `deploy/tika/Dockerfile` adds Tesseract Arabic to `apache/tika:3.3.3.0-full` (used by compose and
  by the worker's tests). Tests run clamd with an EICAR-only signature file, so it starts in seconds.

## Consequences

- Arabic OCR uses Ubuntu's `tessdata_fast` model: good at typical scan sizes, weak on very large text; B-13 benchmarks
  accuracy (best models, PaddleOCR) on real bank scans.
- Watermarks with user ID and time (spec §4.5) are not applied here: they belong to the viewer (B-08); watermarked
  downloads need a worker-side rendering step, as the core never parses content.
- Indexing (`INDEX` jobs) follows in B-07; `index_status` stays `PENDING` until then.
- CI must allow building the Tika image (Docker-in-Docker with network access for `apt-get`).
