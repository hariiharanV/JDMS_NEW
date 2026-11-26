# 0024 — Assisted indexing: upload fields suggested from the file (B-15)

- Status: Accepted
- Date: 2026-11-24

## Context

The product owner asked for the upload form to be filled in from the attached file, in English to start with. The
bank RFP places this later: "AI auto-population of metadata" (23.71, Phase 3) and zone OCR mapped to index fields
(23.38, Phase 4+). The PoC scope (spec §2) does not list it, so this ADR records the decision to build a first,
rule-based version now (backlog B-15) with the engines the PoC already has: Tika for text, Tesseract for OCR. No AI,
no new infrastructure, no new licence.

Before B-15 the upload was one request: metadata first, then the file. Text and OCR come later, in the worker, for the
stored version. The core never parses content (CLAUDE.md rule 6), so the core cannot read values out of the file
itself.

## Decision

**A capture: the file read before the document exists.** `POST /api/v1/captures` (multipart, `metadata` part with
`documentTypeCode`, `branchCode`, optional `folderId` and `classification`, then `file`) stores the file as an
envelope-encrypted blob, records a `capture` row and queues a `CAPTURE` job. 202 with the capture. The UI polls
`GET /api/v1/captures/{id}` until the status leaves `PENDING`; `DELETE` discards it. The upload stays
`POST /documents` unchanged: the user sends the file again with the metadata they confirmed. That costs a second
transfer of the file but leaves the upload, its idempotency, quota, approval and processing paths untouched; reusing
the capture's blob for the version is a later optimisation.

**The worker reads it in one job** (`CaptureReader`): ClamAV, Tika type detection and text, Tesseract OCR when there
is no text layer (the same rule and limits as the B-06 pipeline: size, timeouts, text cap), then `FieldSuggester`.
It reports a `CAPTURE_RESULT` job in the transaction that completes the step, and `FAILED` when it gives up, so a
capture never stays "reading" until it expires. The worker reads the capture and the type's field definitions
(English labels, data types, active list values) directly, read-only, like `VersionStore`.

**Suggestions by label, fixed patterns** (`FieldSuggester`, English): a known label followed by a value on the same
line (`CIF No.: C25112594`) or the next line. Labels are the usual English wordings of the standard banking fields
(CIF, customer name, account number or IBAN, contract, transaction, application and collateral references, expiry
date) plus each custom field's English label and key. Values are read by type: identifiers need a digit, account
numbers six digits, names letters; dates in the usual writings, numeric dates day first (dd/MM/yyyy, as banks in the
region write them) unless the second part cannot be a month; numbers without currency or thousands separators; yes or
no; list values by code or English label, suggested as the code. A value after a separator is `HIGH` confidence; on
the next line, after a label without separator, after a generic label (`Name`, `Reference`) or with conflicting
candidates it is `MEDIUM`. Nothing is suggested without a label: a label word in running text is ignored. No rule is
evaluated at run time (rule 9); per-type templates or zones would be configuration added later.

**Suggestions never in plain columns or job payloads.** Job payloads hold IDs only. The worker writes the suggestions
(field, value, confidence, the label as written) as a new encrypted blob and passes the blob reference in the result;
the core decrypts it only to answer the owner's `GET`. Audit events record counts and outcomes, never values.

**Access.** Starting a capture needs `UPLOAD` for the chosen type, branch, folder and classification, checked through
`AccessPolicy` before the stream is read, with the same validation as the upload (type open to the channel, branch
known, folder in the cabinet, classification between the type's minimum and the caller's clearance). A capture
belongs to the user who attached it: anyone else, in the tenant or another, gets 404. Reading it checks the upload
permission again. A user may have five captures being read at once (`jdms.capture.max-pending`; 429
`CAPTURE_LIMIT`) so the worker cannot be flooded from the upload screen.

**Short-lived.** The file's blob is deleted as soon as the worker's result is applied (it is only needed to be read);
an infected file is deleted too. A capture and its suggestions expire after an hour (`jdms.capture.ttl`); a scheduled
job removes expired ones with their blobs. The web app discards the capture when the file or type changes, after the
upload and when the page is left. The orphan reconciler treats capture blobs as referenced. Every change is audited
(`CAPTURE_STARTED`, `CAPTURE_READ`, `CAPTURE_INFECTED`, `CAPTURE_FAILED`, `CAPTURE_DISCARDED`, `CAPTURE_EXPIRED`).

**The form fills only what the user has not touched.** Once the file and the type are chosen the Upload screen sends
the capture and says it is reading the document; when done it fills the empty, untouched fields, marks each "From the
document ("label")", or "... Please check it." for `MEDIUM`, and counts them. A field the user changes loses its mark.
Values that do not fit the field (a list value not in the list, text over the length) are not filled. The capture
never blocks the upload: on any failure the screen says the document could not be read.

**Passports (added 2026-11-24, after testing with a real scanned passport).** Identity documents print bilingual
labels ("other language / English") with values below them, and OCR jumbles the reading order of their boxes, so
labels alone found nothing. The suggester now also reads a passport's **machine-readable zone** (ICAO 9303 TD3,
`MrzReader`): name (given names, then surname), document number, nationality, date of birth and expiry date,
tolerating OCR noise (spaces, filler read as K, one stray character) and choosing the alignment whose check digits
hold. Values whose check digit holds are `HIGH`. They fill the customer name and expiry date, and a custom field
whose key or English label mentions passport (TEXT), nationality (LOV: the ISO alpha-3 or alpha-2 code, or the value
whose English label starts with the country's name) or birth (DATE). A label after a slash counts as a line start;
"Issue date" also matches "Date of issue" and the reverse; a value on the next line under a text label must have
Latin letters and be neither a date nor another "x / y" label.

## Consequences

- English only. Arabic labels and Arabic-Indic digits need their own label lists and OCR tuning (O-02 samples).
- Mixed identifiers inside Arabic lines remain weak (`docs/perf/ocr-benchmark.md`), as do handwriting, stamps and
  photographed IDs; the user confirms every value, so a wrong suggestion costs a correction, not bad metadata.
- A scan is read twice: once as a capture and again by the normal pipeline after the upload (about 2 s per page with
  Tesseract). Reusing the capture's text for the version is a later optimisation.
- Layout-aware extraction (tables, zones, per-form templates) and AI extraction stay in the later phases of the RFP
  coverage; the `FieldSuggestion` contract (field, value, confidence, evidence) is what they would produce too.
