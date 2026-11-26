const L = require('./lib');
const { p, h1, h2, h3, bullets, steps, code, note, table, figure, cover, toc, build } = L;
const OUT = process.argv[2];
const SHOTS = process.argv[3];
const img = (name, caption) => figure(`${SHOTS}/${name}.png`, caption, 600);

// test case table: id, title, user(s), steps, expected
function cases(rows) {
  return table(['ID', 'Test', 'User(s)', 'Steps', 'Expected result'], rows, [0.8, 1.8, 1.3, 3.6, 3]);
}

const c = [
  ...cover({ title: 'Testing Guide', subtitle: 'Test users, permissions, reference data and step-by-step test scenarios for manual testing',
    audience: 'Testers, business analysts, user acceptance testing (UAT) participants, bank reviewers',
    version: '1.1', date: '26 October 2026' }),
  ...toc(),

  h1('1. Purpose and scope'),
  p('This guide lets a tester exercise every function of JDMS Next by hand: who to sign in as, what each user may do, what data exists, and a numbered set of test scenarios with the expected result of each. It complements the automated tests (section 9), which run on every build.'),
  p('The scenarios follow the proof-of-concept functional specification (`docs/02-poc-functional-spec.md`): upload with dynamic forms, immutable versions, maker-checker approval, committee approval, search with access filtering, recycle bin, records management (legal holds, retention, disposition), administration with maker-checker, audit trail, English and Arabic, and the integration API used by bank systems such as Oracle FLEXCUBE.'),
  h2('1.1 Test environment'),
  table(['Item', 'Value'], [
    ['Application address', 'http://localhost:5180 on the server itself; from other computers https://<server address>:5180'],
    ['Sign-in page', 'Keycloak realm `jdms-local`: http://localhost:8180, or https://<server address>:8543 from other computers'],
    ['Certificate', 'From other computers, import the server\'s `deploy/tls/local/ca.crt` as a trusted authority once, or accept the browser warning'],
    ['API base address', 'http://localhost:8080/api/v1 (users) and /integration/v1 (bank systems)'],
    ['Tenants (banks)', 'BANK1 (main test bank), BANK2 (second bank, isolation tests), PERF (1 million synthetic documents, performance tests)'],
    ['Browsers', 'Chrome, Edge or Firefox, current versions; use a private window or a second browser to be signed in as two users at once'],
  ], [2.5, 7]),
  note('Every scenario that involves two people (maker and checker, two administrators) needs two browser sessions: one normal window and one private window, or two different browsers. Signing out and in again in the same window also works.'),

  h1('2. Test users'),
  p('All test users are defined in the Keycloak realm of the test environment. They share one password: the value of `JDMS_DEV_USER_PASSWORD` in the server\'s `deploy/.env` file. Ask the environment administrator for it; it is deliberately not printed in this guide.'),
  h2('2.1 Bank One (BANK1)'),
  table(['User name', 'Name', 'Role', 'Branch scope', 'Clearance', 'Typical tests'], [
    ['maker1', 'Mona Maker', 'MAKER', '001', 'Confidential', 'Upload, new versions, edit metadata, delete requests'],
    ['checker1', 'Chris Checker', 'CHECKER', '001, 002', 'Confidential', 'Approve, reject, return; download'],
    ['viewer1', 'Vera Viewer', 'VIEWER', '002', 'Internal', 'Read-only access; access-filtering tests'],
    ['records1', 'Rami Records', 'RECORDS_OFFICER', 'All branches', 'Restricted', 'Recycle bin restore, legal holds, retention, disposition'],
    ['tenantadmin1', 'Tara Admin', 'TENANT_ADMIN', 'All', 'Confidential', 'Propose document types and catalog changes'],
    ['tenantadmin2', 'Tariq Admin', 'TENANT_ADMIN', 'All', 'Confidential', 'Approve the other administrator\'s proposals'],
    ['secadmin1', 'Sami Security', 'SECURITY_ADMIN', 'All', 'Restricted', 'Access rules, audit trail'],
    ['secadmin2', 'Salma Security', 'SECURITY_ADMIN', 'All', 'Restricted', 'Approve access-rule proposals'],
    ['committee1, committee2, committee3', 'Kamal, Layla, Omar Committee', 'COMMITTEE_MEMBER', 'All', 'Confidential', 'Committee votes (credit committee)'],
  ], [1.5, 1.6, 1.9, 1.1, 1.4, 2.5]),
  h2('2.2 Bank Two (BANK2)'),
  table(['User name', 'Name', 'Roles', 'Branch scope', 'Clearance', 'Typical tests'], [
    ['bank2user1', 'Bilal Bank Two', 'MAKER, VIEWER', 'All', 'Confidential', 'Tenant isolation: must never see BANK1 data'],
  ], [1.5, 1.6, 1.9, 1.1, 1.4, 2.5]),
  h2('2.3 Performance tenant (PERF)'),
  table(['User name', 'Role', 'Branch scope', 'Clearance', 'Use'], [
    ['perfviewer1', 'VIEWER', 'All', 'Confidential', 'Search on 1 million documents'],
    ['perfbranch1', 'VIEWER', '002, 003, 004', 'Internal', 'Search restricted by branch and clearance'],
    ['perfmaker1', 'MAKER', 'All', 'Confidential', 'Uploads under load'],
    ['perfadmin1', 'TENANT_ADMIN', 'All', 'Confidential', 'Search index rebuild'],
    ['perfsec1, perfsec2', 'SECURITY_ADMIN', 'All', 'Restricted', 'Access rules (revocation tests)'],
  ], [1.8, 2, 1.6, 1.4, 3.2]),
  h2('2.4 System clients (API tests)'),
  table(['Client', 'Role', 'Secret (in deploy/.env)', 'Use'], [
    ['jdms-integration-test', 'INTEGRATION_CLIENT (BANK1)', 'JDMS_INTEGRATION_TEST_CLIENT_SECRET', 'Bank-system integration API (FLEXCUBE-style callers)'],
    ['jdms-migration', 'MIGRATION (BANK1)', 'JDMS_MIGRATION_CLIENT_SECRET', 'Legacy migration tool only'],
  ], [2.2, 2.2, 3, 3]),

  h1('3. What each user may do'),
  p('Access is decided by the access rules of the tenant. Every new tenant starts with the baseline rules below; security administrators can change them (with a second administrator\'s approval). Three conditions must all hold for an action on a document:'),
  bullets(['**Role:** one of the user\'s roles is granted the action (table 3.1).',
    '**Branch:** for roles limited to their own branches ("own"), the document\'s branch must be in the user\'s branch scope.',
    '**Clearance:** the user\'s clearance must be at or above the document\'s classification. The levels, from low to high: Public, Internal, Confidential, Restricted.']),
  p('A document the user may not see behaves as if it did not exist: it is absent from lists, search results, totals and facet counts, and opening its address shows "Document not found".'),
  h2('3.1 Baseline permission matrix'),
  table(['Action', 'Maker', 'Checker', 'Viewer', 'Committee member', 'Records officer', 'Tenant admin', 'Security admin', 'Integration client'], [
    ['View metadata', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes', '', '', 'Yes'],
    ['View content (preview)', 'Yes', 'Yes', 'Yes', 'Yes', 'Yes', '', '', 'Yes'],
    ['Download', 'Yes', 'Yes', '', '', 'Yes', '', '', 'Yes'],
    ['Upload', 'Yes', '', '', '', '', '', '', 'Yes'],
    ['Edit metadata', 'Yes', '', '', '', '', '', '', 'Yes'],
    ['Check out / new version', 'Yes', '', '', '', '', '', '', 'Yes'],
    ['Delete (to recycle bin)', 'Yes', '', '', '', '', '', '', ''],
    ['Approve (tasks)', '', 'Yes', '', 'Yes', '', '', '', ''],
    ['Restore from recycle bin', '', '', '', '', 'Yes', '', '', ''],
    ['Legal holds, retention, disposition', '', '', '', '', 'Yes', '', '', ''],
    ['Administer document types and catalog', '', '', '', '', '', 'Yes', '', ''],
    ['Administer access rules', '', '', '', '', '', '', 'Yes', ''],
    ['View audit trail', '', '', '', '', '', '', 'Yes', ''],
    ['Branches covered', 'Own', 'Own', 'Own', 'All', 'All', 'All', 'All', 'All'],
  ], [2.6, 0.9, 1, 0.9, 1.1, 1, 1, 1, 1.1], { size: 16 }),
  h2('3.2 Rules that always apply'),
  bullets(['**Separation of duties:** nobody approves their own upload, version or proposal; the task is not offered to its maker, and the server refuses it ("You cannot decide your own proposal").',
    '**Maker-checker for administration:** document types, fields, lists of values, folders, cabinets, user scopes and access rules are proposals until a second administrator approves them.',
    '**Immutable versions:** a stored version never changes; a new file is always a new version.',
    '**Audit:** every change, and every denied attempt, is written to a tamper-evident audit trail.']),

  h1('4. Reference data in BANK1'),
  p('A fresh test environment (the `local` profile) contains this catalog for BANK1. Tests that create new document types add to it.'),
  h2('4.1 Cabinets and document types'),
  table(['Cabinet', 'Document type', 'Approval', 'Default classification', 'Required standard fields', 'Type fields (* required)'], [
    ['Customers', 'Passport', 'One checker', 'Confidential', 'CIF, expiry date', 'Passport number*, Nationality* (list), Issue date*, Place of issue'],
    ['Customers', 'National ID card', 'One checker', 'Confidential', 'CIF, expiry date', 'National ID number*, Issue date*'],
    ['Customers', 'Account opening form', 'One checker; becomes a record on approval', 'Confidential', 'Account number, CIF', 'Account type* (list), Date opened*, Relationship officer, Joint account (yes/no)'],
    ['Loans and credit', 'Loan agreement', 'Several stages: branch review; credit committee if amount above 100,000; record on approval', 'Confidential', 'CIF, contract reference', 'Facility type* (list), Amount*, Currency* (list), Origination date*, Tenor (months)'],
    ['Loans and credit', 'Collateral deed', 'One checker; record on approval', 'Confidential', 'CIF, collateral code', 'Collateral type* (list), Valuation amount*, Valuation date*, Deed number'],
    ['Loans and credit', 'Salary certificate', 'No approval', 'Restricted', 'CIF', 'Employer*, Monthly salary* (sensitive: never searchable), Issue date*'],
  ], [1.3, 1.6, 2.2, 1.4, 1.6, 2.9]),
  p('For all six types, metadata changes and deletions need approval. Lists of values: Nationality, Account type, Facility type, Currency, Collateral type (each with English and Arabic labels).'),
  h2('4.2 Branches'),
  table(['Tenant', 'Branches'], [['BANK1', '001 Head Office, 002 Main Street, 003 Airport'], ['BANK2', '001 Head Office'],
    ['PERF', '001 to 020']], [2, 7]),
  h2('4.3 Test files'),
  bullets(['Small PDFs with a text layer (any generated or scanned-and-OCRed PDF) for normal uploads.',
    'A scanned page as PNG or JPEG, or an image-only PDF, to test OCR (English and Arabic).',
    'A Word or Excel file, to test PDF preview conversion.',
    'The EICAR antivirus test file (a harmless standard test string, from eicar.org) to test quarantine. Your own antivirus may block it; use the test machine.',
    'An empty file and a file above 100 MB, to test the limits.']),

  h1('5. Before you start'),
  steps(['Confirm with the administrator that the environment is up: the Installation Guide\'s verification checklist (chapter 9) has passed.',
    'Get the test password and, for API tests, the integration client secret.',
    'Use fresh values in every run for fields that must be unique to recognise your documents, for example a CIF such as C2026102501 or a title with your initials and the time.',
    'Keep a note of each document\'s title and CIF: you will look for them as other users.',
    'Record every result in the test log (section 8). A failed expected result is a defect; report it with the template in section 8.2.']),

  h1('6. Test scenarios'),
  p('Each scenario lists the user(s), the steps and the expected result. Scenarios build on each other within a group; run the groups in order the first time.'),

  h2('6.1 Sign-in, navigation and language (SIGN)'),
  cases([
    ['SIGN-01', 'Sign in', 'maker1', 'Open the application, click Sign in, enter user name and password', 'Home page: "Welcome, Mona Maker", tenant BANK1 in the header, tasks and recent documents'],
    ['SIGN-02', 'Wrong password', 'any', 'Sign in with a wrong password', 'Keycloak shows "Invalid username or password"; after repeated failures the account is temporarily locked (brute-force protection)'],
    ['SIGN-03', 'Menus follow the role', 'maker1, checker1, viewer1, records1, tenantadmin1, secadmin1', 'Sign in as each and compare the menu', 'maker1: Home, Browse, Search, Tasks, Upload. records1 adds Recycle bin and Records. tenantadmin1 sees Administration. secadmin1 sees Administration (access rules) and Audit. viewer1 has no Upload'],
    ['SIGN-04', 'Arabic, right to left', 'maker1', 'Switch the language to العربية in the header; visit Home, Upload, a document', 'All labels in Arabic; layout mirrored (menu starts on the right); the choice is remembered after reloading'],
    ['SIGN-05', 'Keyboard use', 'maker1', 'Press Tab right after the page loads; press Enter on "Skip to content"; move through the menu with Tab', 'A visible focus ring on every element; the skip link jumps to the page content'],
    ['SIGN-06', 'Sign out', 'maker1', 'Click the user name, then Sign out; press Back in the browser', 'Back on the sign-in page; protected pages ask for sign-in again'],
  ]),

  h2('6.2 Upload and processing (UPL)'),
  cases([
    ['UPL-01', 'Upload with the dynamic form', 'maker1', 'Upload: Cabinet Customers, Document type Passport; fill Title, CIF, Expiry date, Passport number, Nationality, Issue date; choose a PDF; Upload', 'The type\'s fields appear after the type is chosen. After upload the document opens with status "Pending approval"'],
    ['UPL-02', 'Required fields', 'maker1', 'Repeat UPL-01 but leave Passport number and the CIF empty; Upload', 'Upload is refused; each missing field is marked "… is required"; nothing is stored'],
    ['UPL-03', 'Processing', 'maker1', 'Open the document of UPL-01 after about 30 seconds', 'The Overview shows the watermarked PDF preview beside the details; the Versions tab lists version 1.0'],
    ['UPL-04', 'OCR of a scan', 'maker1 then checker1', 'Upload a scanned page (image) with Arabic or English text; approve it; wait one minute; search for a word of the scanned text', 'The document is found by a word that exists only in the image'],
    ['UPL-05', 'Office file preview', 'maker1', 'Upload a Word document', 'After processing, the Overview preview shows a PDF rendition of the Word file'],
    ['UPL-06', 'Duplicate warning', 'maker1', 'Upload exactly the same file again for the same CIF and type', 'Upload succeeds with the warning "The same file was uploaded before."'],
    ['UPL-07', 'Infected file', 'maker1', 'Upload the EICAR test file', 'Processing status "Quarantined"; preview and download refused ("This file was quarantined by the antivirus scan"); a notification for the uploader is created (`GET /api/v1/me/notifications`)'],
    ['UPL-08', 'Empty and oversize files', 'maker1', 'Upload an empty file; then a file larger than the cabinet limit (100 MB)', '"The file is empty." / "The file is too large."; nothing stored'],
    ['UPL-09', 'Classification above clearance', 'maker1', 'Upload a Salary certificate (cabinet Loans and credit; default classification Restricted). Then try again with classification Confidential', 'First: refused, "You cannot classify a document above your own clearance". Second: refused, "Classification cannot be lower than the type\'s default". Nothing stored'],
    ['UPL-10', 'Branch outside the user\'s scope', 'maker1', 'Upload a passport and choose branch 002', 'Refused: "You are not allowed to do this." (maker1 may upload only for branch 001)'],
  ]),
  note('UPL-09 and UPL-10 show the clearance and branch rules at upload: a user can only file documents they would be allowed to see.'),

  h2('6.3 Approval workflow (APR)'),
  ...img('10-tasks', 'The Tasks inbox of checker1'),
  cases([
    ['APR-01', 'Approve', 'maker1, checker1', 'maker1 uploads a passport (title A). checker1 opens Tasks, clicks the item, checks the preview and details beside it, clicks Approve', '"Decision recorded" and the next task opens; the document becomes Active; Approval history shows Submitted (maker1) and Approved (checker1)'],
    ['APR-02', 'Reject needs a comment', 'maker1, checker1', 'maker1 uploads title R. checker1 clicks Reject without a comment; then writes a comment and clicks Reject again', 'Without a comment nothing is sent: the comment field is marked "Write why you are rejecting it, then choose Reject again." With it: status Rejected; the comment is in the approval history'],
    ['APR-03', 'Return for changes', 'maker1, checker1', 'maker1 uploads title T. checker1 chooses Return for changes with a comment', 'Status Draft; maker1 finds the task under Tasks > Returned to you (and on Home); after Resubmit, checker1 gets a new approval task (round 2)'],
    ['APR-04', 'No self-approval', 'maker1', 'maker1 looks for the approval task of their own upload', 'Not in maker1\'s inbox; calling the decision API as maker1 is refused'],
    ['APR-05', 'Branch scope of the checker', 'jdms-integration-test, checker1', 'Upload a passport for branch 003 through the API (API-01 with "branchCode":"003")', 'checker1 (branches 001, 002) gets no task for it; it waits for a checker whose scope covers branch 003'],
    ['APR-06', 'Multi-stage approval', 'maker1, checker1', 'Upload a Loan agreement with amount 50,000', 'One stage, "Branch review" (CHECKER, due in 24 hours); after approval the document is Active and declared a record'],
    ['APR-07', 'Committee approval', 'maker1, checker1, committee1-3', 'Prerequisite: an administrator created committee CREDIT_COMMITTEE with the three members (API, see note). Upload a Loan agreement with amount 250,000; checker1 approves stage 1; committee members vote', 'Stage 2 "Credit committee" is offered to the members as a Committee vote; the document is approved when the quorum is reached; votes are listed in the approval history'],
    ['APR-08', 'SLA display', 'checker1', 'Look at the deadline of each task in the inbox list, and the Due soon and Overdue filters', 'On time / Due soon / Overdue according to the stage SLA (24 h single and branch review, 48 h committee)'],
  ]),
  note('Committees are configured through the administration API in the PoC (`POST /api/v1/admin/committees`, tenant administrator, then a second administrator approves the proposal). Members must have signed in once.'),

  h2('6.4 Documents and versions (DOC)'),
  cases([
    ['DOC-01', 'Document details', 'maker1', 'Open an Active document', 'Header with where it is filed, title, status, classification and current version; tabs Overview (preview beside details and approval), Versions, Approval history'],
    ['DOC-02', 'Viewer controls', 'maker1', 'Overview preview: next and previous page (on a document with several pages), zoom in and out', 'Pages and zoom change; "Page x of y" is correct'],
    ['DOC-03', 'Download', 'maker1', 'Click Download', 'The original file is downloaded, identical to the uploaded one'],
    ['DOC-04', 'New version', 'maker1, checker1', 'New version: comment, choose a file, Check in; checker1 approves', 'Versions tab lists 1.0 and 2.0 with their files, sizes and comments; 2.0 becomes current after approval; 1.0 is unchanged and still downloadable'],
    ['DOC-06', 'Metadata change with approval (API)', 'jdms-integration-test, checker1', 'Read the document with `curl -i .../api/v1/documents/<id>` and note the ETag header; send `curl -X PATCH -H "If-Match: <etag>" -H "Content-Type: application/merge-patch+json" -d \'{"expiryDate":"2032-01-31"}\' .../api/v1/documents/<id>` with the integration token (section 6.11); checker1 approves the "Metadata change" task', 'The PATCH answers 202 with a pending change; the expiry date changes only after checker1 approves. (Editing details on the screens is not in this release.)'],
    ['DOC-07', 'History of one document', 'maker1, secadmin1', 'Copy the document ID from the address bar (/documents/<id>). secadmin1 opens Audit and pastes that ID into On', 'Upload, views, previews, downloads, approvals and deletions of that document, with time, actor and outcome. (The document\'s own Audit tab appears only for users who may both view the document and the audit trail; no baseline role has both.)'],
  ]),

  h2('6.5 Browse and search (SRCH)'),
  cases([
    ['SRCH-01', 'Browse a cabinet', 'maker1', 'Browse: choose cabinet Customers; click a document in the list', 'Folder tree, document list and a properties panel for the selected document; Open shows the document'],
    ['SRCH-02', 'Search by word', 'maker1', 'Search: type "passport", Search', 'Results with titles, type, branch, status; highlighted text snippets; Refine panel with counts per type, branch, status, cabinet'],
    ['SRCH-03', 'Refine with facets', 'maker1', 'Click a value in Refine (e.g. one document type)', 'Results and counts narrowed to that value'],
    ['SRCH-04', 'Search by CIF', 'maker1', 'Search for the CIF used in UPL-01', 'Exactly the documents of that customer'],
    ['SRCH-05', 'Search in Arabic', 'maker1', 'Search an Arabic word that appears in an uploaded Arabic document', 'Found (Arabic analyser: different spellings of alef and endings match)'],
    ['SRCH-06', 'Content searchable after processing', 'maker1, checker1', 'Upload and approve a PDF with a distinctive word in its text; search for that word at once and again after one minute', 'Metadata (title, CIF) is found within seconds; the word in the content after processing'],
    ['SRCH-07', 'Search never leaks', 'viewer1', 'As viewer1 (branch 002, Internal), search for the CIF of a Confidential document in branch 001', '"No documents found": not in results, totals or facet counts'],
    ['SRCH-08', 'Sensitive fields', 'records1', 'Search for the monthly salary value of a Salary certificate', 'Not found: sensitive fields are never indexed'],
  ]),
  ...img('15-search-results', 'Search results with the Refine panel'),

  h2('6.6 Access control and tenant isolation (SEC)'),
  cases([
    ['SEC-01', 'UI hides what a user cannot do', 'checker1', 'Open an Active document of branch 001', 'Download is shown; New version is not, and More actions offers Copy link but no Delete'],
    ['SEC-02', 'Direct address of a hidden document', 'viewer1', 'Paste the address (/documents/<id>) of a Confidential document of branch 001', '"Document not found"; the attempt is recorded as DENIED in the audit trail'],
    ['SEC-03', 'Tenant isolation', 'bank2user1', 'Search for a BANK1 CIF; paste the address of a BANK1 document', 'Nothing found; "Document not found". No BANK1 data is ever visible in BANK2'],
    ['SEC-04', 'Deny rule takes effect immediately', 'secadmin1, secadmin2, viewer1', 'secadmin1 proposes a DENY rule (Security > Access rules) on one document for role VIEWER; secadmin2 approves; viewer1 reloads the document and searches for it', 'Immediately after approval viewer1 gets "Document not found" and the document disappears from search (within 60 seconds on any other server)'],
    ['SEC-05', 'API without a token', 'none', '`curl -i http://localhost:8080/api/v1/documents`', 'HTTP 401 with a JSON problem body, code AUTH_UNAUTHENTICATED'],
    ['SEC-06', 'Viewers preview but do not download', 'jdms-integration-test, viewer1', 'Upload through the API a passport for branch 002 with "classification":"INTERNAL" and have it approved; viewer1 opens it', 'viewer1 sees the details and the preview; there is no Download button, and the download address answers 403'],
  ]),

  h2('6.7 Deletion and recycle bin (BIN)'),
  cases([
    ['BIN-01', 'Delete with approval', 'maker1, checker1', 'Open an Active document, More actions > Delete…, Delete document; checker1 approves the deletion task', 'Deletion goes to approval first; after approval the document is Deleted and no longer listed or found'],
    ['BIN-02', 'Restore', 'records1', 'Recycle bin: find the document, Restore', '"Document restored"; it is back with its previous status, versions and history'],
    ['BIN-03', 'Delete blocked by a legal hold', 'records1, maker1', 'Place a legal hold on the customer (REC-02), then maker1 tries to delete one of the customer\'s documents', '"The document is on legal hold."'],
  ]),

  h2('6.8 Records management (REC)'),
  cases([
    ['REC-01', 'Retention policies', 'records1', 'Records > Retention policies; New policy', 'Policies with name, start (creation date, expiry date or event), period, action at the end, and the document types they apply to; a new policy is a proposal another officer approves'],
    ['REC-02', 'Legal hold by CIF', 'records1', 'Records > Legal holds > New legal hold: name, reason, "Hold all documents of CIF" with a test CIF; Place hold', '"Legal hold placed"; the hold lists the customer\'s documents; each shows "On legal hold" in its header'],
    ['REC-03', 'Release a hold', 'records1', 'Release… on the hold of REC-02; Release hold in the dialog', 'Status Released; documents can be deleted again (unless another hold applies)'],
    ['REC-04', 'Declared records', 'maker1', 'Try to delete an approved Account opening form', '"The document is a declared record." (protected until its retention ends)'],
    ['REC-05', 'Disposition', 'records1, a second records officer', 'Records > Disposition > List documents due; the second officer chooses Approve and destroy… and types the number of documents', 'With nothing due: "No documents are due". With documents past retention: a batch waits for a second officer; "Approve and destroy" destroys the content and leaves a tombstone; documents on hold are never included'],
  ]),

  h2('6.9 Administration (ADM)'),
  ...img('22-admin-new-type', 'Proposing a new document type with fields'),
  cases([
    ['ADM-01', 'Propose a document type', 'tenantadmin1', 'Document types > New document type: cabinet Customers, code TEST_<initials>, a name (the Arabic name may stay empty), approval One checker; Add field: key reference_no, label, type Text, required; Propose', '"Proposed; another administrator must approve it." The type is not yet available in Upload'],
    ['ADM-02', 'Own proposal cannot be approved', 'tenantadmin1', 'Pending changes: look at your proposal', 'No Approve button for your own proposal'],
    ['ADM-03', 'Second administrator approves', 'tenantadmin2', 'Pending changes: Approve the proposal of ADM-01', '"Decision recorded"; the type appears in Document types and in the Upload form with its field'],
    ['ADM-04', 'Reject with a reason', 'tenantadmin1, tenantadmin2', 'Propose another type; tenantadmin2 rejects it with a comment', 'The proposal is Rejected; nothing changes in the catalog'],
    ['ADM-05', 'Access rules list', 'secadmin1', 'Security > Access rules; click a cell', 'A table of roles and actions with the baseline rules; the cell shows the rule behind it (e.g. Checker, Approve: Baseline CHECKER, their own branches); narrower rules listed below'],
    ['ADM-06', 'Arabic off and on', 'tenantadmin1, tenantadmin2, maker1', 'Languages: switch Arabic off, Propose change; tenantadmin2 approves under Pending changes; maker1 signs in again. Then switch it back on the same way', 'With Arabic off: no language switch in the top bar, screens in English, no Arabic boxes in Document types. With it on again: the switch is back'],
  ]),

  h2('6.10 Audit trail (AUD)'),
  cases([
    ['AUD-01', 'Audit search', 'secadmin1', 'Audit: choose a period and an action (e.g. Document uploaded), or enter an actor', 'Matching events with time, action, actor, resource, outcome and request ID'],
    ['AUD-02', 'Denied attempts', 'secadmin1', 'Filter outcome Denied after SEC-02', 'The viewer\'s denied attempt is listed'],
    ['AUD-03', 'Hash chain verification', 'secadmin1', 'Click Verify now', '"Chain intact: all N events verified"; any change to an audit row would show "Chain broken at event …"'],
    ['AUD-04', 'Audit is read-only', 'secadmin1', 'Look for edit or delete options on audit events', 'None; the database user of the application cannot change audit rows'],
  ]),

  h2('6.11 Integration API for bank systems (API)'),
  p('These tests use the command line (`curl`, `jq`) on the test server with the integration client secret.'),
  ...code(['set -a; . deploy/.env; set +a',
    'TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=jdms-integration-test \\',
    '  -d "client_secret=$JDMS_INTEGRATION_TEST_CLIENT_SECRET" \\',
    '  http://localhost:8180/realms/jdms-local/protocol/openid-connect/token | jq -r .access_token)',
    'KEY=$(uuidgen)',
    'curl -s -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \\',
    '  -F \'metadata={"documentTypeCode":"PASSPORT","title":"API passport","branchCode":"001","cif":"C2026API01",\\',
    '"expiryDate":"2030-01-01","customFields":{"passport_no":"A1234567","nationality":"OM","issue_date":"2020-01-01"}};type=application/json\' \\',
    '  -F \'file=@passport.pdf;type=application/pdf\' http://localhost:8080/integration/v1/documents | jq']),
  cases([
    ['API-01', 'Upload through the API', 'jdms-integration-test', 'Run the commands above', 'HTTP 201 with the document ID; source system INTEGRATION:jdms-integration-test; the document appears in the UI'],
    ['API-02', 'Idempotent retry', 'jdms-integration-test', 'Send the same request again with the same Idempotency-Key', 'The same response and the same document ID; no second document'],
    ['API-03', 'Download', 'jdms-integration-test', '`curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/integration/v1/documents/<id>/content -o out.pdf`', 'The original file, byte for byte'],
    ['API-04', 'Validation errors', 'jdms-integration-test', 'Upload without the CIF', 'HTTP 422 problem+json listing the missing field'],
  ]),

  h2('6.12 Optional: resilience (RES)'),
  cases([
    ['RES-01', 'Worker stopped', 'maker1 + administrator', 'The administrator stops jdms-worker; maker1 uploads; the worker is started again', 'The upload succeeds while the worker is down (Pending processing); processing completes after the restart'],
    ['RES-02', 'Search engine restarted', 'maker1 + administrator', 'Restart OpenSearch while uploading', 'Uploads succeed; search answers "Search is unavailable at the moment" while it restarts; everything is searchable afterwards'],
  ]),

  h1('7. Screens for reference'),
  p('The User Manual describes every screen in detail. These screenshots help testers recognise where a scenario starts.'),
  ...img('05-document-pending', 'A new upload waiting for approval'),
  ...img('11-task-open', 'A task opened from the inbox, with the decision buttons'),
  ...img('25-audit', 'The audit trail with hash-chain verification'),

  h1('8. Recording results'),
  h2('8.1 Test log'),
  table(['Date', 'Tester', 'Scenario ID', 'Result (Pass / Fail / Blocked)', 'Defect ID', 'Notes'],
    [['', '', '', '', '', ''], ['', '', '', '', '', ''], ['', '', '', '', '', '']], [1.2, 1.4, 1.3, 2, 1.2, 2.5]),
  h2('8.2 Defect report template'),
  table(['Field', 'What to write'], [
    ['Title', 'Short statement of what is wrong'],
    ['Scenario ID and step', 'e.g. APR-02, step 2'],
    ['User and tenant', 'e.g. checker1, BANK1'],
    ['Steps to reproduce', 'Numbered steps from a known starting point'],
    ['Expected result', 'From this guide'],
    ['Actual result', 'What happened, with the exact message'],
    ['Evidence', 'Screenshot; the Request ID shown in error details or in the audit trail; time'],
    ['Severity', 'Critical (data loss, security), High (function unusable), Medium, Low (cosmetic)'],
  ], [2.5, 7]),
  note('Never paste passwords, tokens or customer data into a defect report. The Request ID identifies the server log lines without exposing anything.'),

  h1('9. Automated tests (for reference)'),
  table(['Suite', 'Command', 'What it covers'], [
    ['Back-end build', 'cd backend && ./mvnw verify', 'About 480 automated tests: domain rules, 29 integration test classes against real PostgreSQL, OpenSearch, Keycloak, S3, ClamAV, Tika and Gotenberg; authorization matrix (allowed and denied) for every endpoint; tenant isolation; module structure; licences'],
    ['Front end', 'cd frontend && npm test', 'Translations complete in both languages; components; error messages'],
    ['UI journeys', 'cd frontend && npm run e2e:stack', 'Eight end-to-end journeys in a real browser against the running stack, including right-to-left screenshots'],
    ['Performance', 'docs/perf/README.md', 'Gatling load tests, resilience rehearsal, OCR benchmark, backup and restore'],
  ], [1.8, 3, 5]),
];

build(OUT, 'Testing Guide', c);
