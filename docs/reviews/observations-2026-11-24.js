// Response to "JDMS Next – Functional Testing Observations, Missing Functionalities & API Review" (24 Nov 2026).
// Build: node docs/reviews/observations-2026-11-24.js docs/reviews/JDMS-Next-Observations-Response-2026-11-24.docx
const L = require('../manuals/build/lib');
const { p, h1, h2, bullets, table, note, cover, build } = L;
const OUT = process.argv[2];

const c = [
  ...cover({ title: 'Response to Testing Observations', subtitle: 'Which observations are valid, which are already addressed, and what needs further development',
    audience: 'JMR product, testing and integration teams', version: '1.0', date: '24 November 2026' }),

  h1('1. Summary'),
  p('Thank you for the review. Each observation was checked against the product as built (code, tests and the test server 192.168.2.202). Of the 12 missing functionalities: **5 are already available** (some not visible because they run in the background or live in Keycloak), **3 were built or fixed today**, and **4 are valid gaps** planned for later phases. Of the API observations, upload, download and delete exist for bank systems; FLEXCUBE compatibility needs the bank\'s sample payloads to verify; bulk APIs are valid gaps; approval by a system is not offered on purpose.'),
  table(['Status', 'Meaning', 'Items'], [
    ['Available', 'Exists today; how to see it is given below', 'OCR, scheduler (system jobs), folder-level access rules, migration utility, password policies (Keycloak)'],
    ['Built or fixed today', 'Developed in response to this review', 'Folder structure screen, user management screen, workflow (committee) screen, image preview'],
    ['Partly available', 'The core exists; part of the request is a later item', 'Workflow setup (flow designer), document preview (TIFF), backup (screen), FLEXCUBE compatibility'],
    ['Valid gap', 'Not built; planned', 'Bulk upload (screen and API), bulk delete API, reports'],
    ['Not planned (by design)', 'Conflicts with a security rule; alternative offered', 'Approval and bulk approval by a bank system'],
  ], [2.2, 3.3, 4.0]),

  h1('2. Missing functionalities'),
  table(['Observation', 'Finding', 'Status and next step'], [
    ['OCR', 'Available since B-06. Every scanned image and image-only PDF is read by Tesseract (English and Arabic) in the background, and its text is searchable. It is not visible as a screen: search for a word that appears only inside a scanned document to see it. Since B-15 the Upload screen also reads the file and fills the form (passports included).', 'Available. Benchmark: docs/perf/ocr-benchmark.md'],
    ['Workflow setup', 'Approval route per document type (none, one checker, several stages, committee) on Admin > Document types; who approves on Security > Access rules; committees on Admin > Committees (new, B-17). Not available: designing new flows, stages or amount thresholds on screen; they are routing tables (DMN) changed by a JMR release.', 'Partly available. Approval Workflow Setup Guide explains the set-up. Flow designer: later phase (proposed B-24)'],
    ['Bulk upload', 'Valid. The Upload screen files one document at a time; the integration API files one per call. Only the migration utility imports in bulk (from the legacy JDMS).', 'Valid gap. Proposed B-21 (screen: many files with a metadata sheet; API: batch upload). RFP 23.17, Phase 2'],
    ['Backup', 'By design a platform task, not a screen (functional spec section 2: "backup/recovery screens replaced by platform backup plus recycle bin"). Backup and restore of database, object store and keys are scripted and were rehearsed with timings (perf/backup, docs/perf/backup-restore.md). Users restore deleted documents from the Recycle bin.', 'Available at platform level. Production: point-in-time recovery and object-store replication (RFP 23.56, Phase 2)'],
    ['Scheduler process', 'System schedules run today: retention and disposition (daily), audit chain verification (daily), storage reconciliation (daily), processing and indexing queues (continuous), event redelivery, clean-ups. Not available: user-configurable scheduled jobs or scheduled reports (out of PoC scope).', 'Available for system jobs. Please tell us which activities you expected to schedule; scheduled reports come with B-22'],
    ['User management', 'Security > Users (new today, B-17): roles, branches and clearance of every user; branches and clearance changed with a second administrator\'s approval. Names, e-mail, passwords, roles and disabling users are managed in Keycloak, the bank\'s sign-in service (ADR-0007): one place for identities, shared with other bank systems.', 'Built today (B-17) + Keycloak. Approval Workflow Setup Guide section 2'],
    ['Folder structure creation', 'Valid: the folder service existed (API only), with no screen.', 'Built today (B-19): Admin > Folders: create, sub-folders, rename, move, delete (empty only), second administrator approves'],
    ['Folder-level access rules', 'Available: Security > Access rules > Propose a rule > Applies to: Folder; the rule covers the folder and its sub-folders. The form asked for the folder\'s ID, which was hard to use.', 'Available; improved today (B-19): the folder is picked from the cabinet\'s tree'],
    ['Migration utility', 'Available since B-12: the jdms-migration command-line tool catalogues the legacy JDMS, decrypts and imports documents with metadata and versions, and writes a reconciliation report. It has no screen (run by JMR or the bank\'s IT).', 'Available. Running it on real data needs a copy of a legacy database and its key (open item O-04)'],
    ['Reports', 'Valid. Today: the Audit screen (filters by user, action, document and date) and search with facets. No standard reports (document volumes, pending approvals, user activity, retention due).', 'Valid gap. Proposed B-22 standard reports. RFP 23.59, Phase 2'],
    ['Password policies', 'Available in Keycloak (Realm settings > Authentication > Policies: length, upper/lower case, digits, special characters, not username, history, expiry; brute-force lockout). Lockout is on; the complexity policy is not yet configured on the test realm.', 'Available (configuration). Set the bank\'s policy in Keycloak at installation; JMR adds it to the installation guide'],
    ['Document preview', 'Partly valid. Word, Excel, PowerPoint, RTF, OpenDocument, CSV and text files are converted to PDF and previewed. JPEG, PNG, GIF and WebP images did not display because the viewer only drew PDFs: fixed today (B-19). TIFF and BMP (common for scans) are not previewed yet; e-mail files (.msg, .eml) neither.', 'Images fixed today (B-19). TIFF/BMP: proposed B-20 (convert to PDF in the worker)'],
  ], [1.8, 4.9, 2.8]),

  h1('3. APIs and FLEXCUBE integration'),
  table(['API', 'Finding', 'Status and next step'], [
    ['Document upload', 'Available for bank systems: POST /integration/v1/documents (OAuth2 client credentials, business keys, idempotent retries). New today: folderPath, and lookup by the system\'s own reference (externalRef).', 'Available. OpenAPI file and FLEXCUBE Integration Guide delivered (B-18)'],
    ['Document download', 'Available: GET /integration/v1/documents/{id}/content and /preview; list by CIF, account, contract, application or own reference.', 'Available'],
    ['Document delete', 'Available: DELETE /integration/v1/documents/{id} (recycle bin; approval when the type requires it). The client needs the delete permission, which the standard integration role does not have.', 'Available; grant the permission to the FLEXCUBE client if required'],
    ['Document approval', 'Not offered to bank systems on purpose: approval is a person\'s maker-checker decision (separation of duties, functional spec section 6). People approve in Tasks (the web application\'s API /api/v1/tasks is for signed-in users). If documents filed by FLEXCUBE must not wait for approval, their document type is given "No approval".', 'Not planned (by design). Discuss the business need'],
    ['FLEXCUBE compatibility', 'The new API differs from the legacy JDMS calls on purpose (OAuth2 instead of trusted user IDs, multipart instead of Base64 XML, UUID references, RFC 9457 errors). The mapping of every legacy field is in the Integration Guide section 8. Verifying FCUBS needs its sample payloads and a FLEXCUBE test environment (open item O-03).', 'Partly available. Two options: FLEXCUBE adopts the new API (recommended), or JMR adds legacy-compatible endpoints (/jdms/document-upload ...) mapping onto it (proposed B-23)'],
    ['Bulk upload API', 'Valid gap.', 'Proposed B-21 (with the bulk upload screen)'],
    ['Bulk delete API', 'Valid gap for systems. Bulk destruction at the end of retention exists as disposition batches (approved by a second records officer).', 'Proposed with B-21 if a business case exists'],
    ['Bulk approval API', 'Not offered to systems (see Document approval). Approving several tasks at once on screen is a reasonable request for checkers.', 'Not planned for systems; on-screen bulk approval proposed B-25'],
  ], [1.8, 4.9, 2.8]),

  h1('4. What changed today'),
  bullets(['**B-17** Admin > Committees and Security > Users screens (deployed to the test server).',
    '**B-18** OpenAPI description of the integration API, FLEXCUBE Integration Guide, folderPath and externalRef (deployed).',
    '**B-19** Admin > Folders screen, folder picker in access rules, image previews (being deployed after its tests).',
    '**Assisted indexing** reads passports (machine-readable zone and bilingual labels) and fills the Upload form (deployed).']),

  h1('5. Proposed backlog items'),
  table(['Item', 'Scope', 'Size'], [
    ['B-20 Preview of TIFF and BMP', 'Convert TIFF (multi-page) and BMP originals to a PDF rendition in the worker', 'Small'],
    ['B-21 Bulk upload', 'Screen: several files plus a metadata sheet (CSV/Excel) with validation per row; integration API batch upload; optional bulk delete', 'Medium'],
    ['B-22 Standard reports', 'Documents by type/branch/status, pending approvals and ageing, user activity, retention due; export to Excel/CSV; optional schedule', 'Medium'],
    ['B-23 Legacy-compatible FLEXCUBE endpoints', 'Only if FLEXCUBE cannot change: /jdms/document-upload, -download, -details mapped onto the new API with OAuth2', 'Medium'],
    ['B-24 Approval flow designer', 'Edit stages, roles, amount thresholds and committees per document type on screen (DMN tables)', 'Large'],
    ['B-25 Bulk approval on screen', 'Checkers select several tasks and approve them at once (each still recorded and checked)', 'Small'],
  ], [3.0, 5.0, 1.5]),
  note('B-16 (upload from the already-read copy, no second transfer or OCR) is already in the backlog.'),
];

build(OUT, 'Response to Testing Observations', c);
