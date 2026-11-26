# 07 — RFP coverage: Loan Management Solution RFP, sections 22–23

Assessment of the DMS requirements in the bank's "New RFP for Loan Management Solution" (section 22
Document Management, section 23 Enterprise Document & Record Management System) against the JDMS Next
roadmap. Prepared 24 Oct 2026. This is planning input, not a bid response.

## Method

- Sections 22 and 23 were split into 108 distinct requirements; repeated requirements (the RFP lists
  several twice) are merged. Section 22 items about disbursement, reporting and product configuration
  belong to the loan system, not the DMS, and are excluded.
- 5 items are not software (scanner hardware, single-OEM, licence terms, reference sites, services);
  they are excluded from the percentage. That leaves **103 product requirements**.
- Score: Full = 1, Partial = 0.5, at the phase where the roadmap delivers it.

## Summary

| By the end of | Coverage of 103 product requirements |
| --- | --- |
| PoC (month 2) | 15% |
| Phase 1, core MVP (month 8) | 41% |
| Phase 2, migration + records (month 12) | 55% |
| Phase 3, AI + scale (month 18) | **58%** |
| Plus the later packages below | 87% |

The remaining 13% after all packages: CMIS, DoD 5015.02 / NRAA certification, and partial items
(Lotus Notes archiving, biometric/three-factor authentication, browser print/copy prevention).

**Where the roadmap is already strong (by month 18):** architecture (91%), administration (90%),
security (83%), viewer basics (79%), repository (75%), AI (75%), records (71%), search and audit (100%).
The RFP's "React frontend with Java backend" and "open-source DBMS preferred" match our stack exactly.

**Where it is weak:** capture and scanning (10%), annotation and redaction (0%), physical records and
space management (12%), Office/email integration (0%), mobile app (0%), dashboards and report designer.
Section 23 reads like a public-sector EDRMS template built around a scanning/capture suite.

## Later packages (Phase 4+), in suggested order of value for bank deals

| Package | Requirements | Approach | Rough size |
| --- | --- | --- | --- |
| A. Loan document checklist, deferral and generation | 22.d–22.g | Checklist per product, match by document type/IDP, deferral with approval and due date, template-based generation. Strong fit with JMR's loan origination products; decide whether it lives in the DMS or the LOS | Medium |
| B. Annotation and redaction | 23.25, 23.29–23.33 | Annotation layer stored separately from originals; redacted renditions governed by access rules; stamps with user/time | Medium |
| C. Capture and imaging | 23.38, 23.85–23.90, 23.92–23.94 | Image-clean-up and barcode steps in the worker pipeline; batch capture with QC queues; zone OCR ("rubber band") in the viewer; scanner connection through a scanning SDK or local agent (partner decision) | Large |
| D. Physical records and space management | 23.81–23.84 | Builds on retrieval requests: locations, boxes, racks, barcode labels, movement and courier tracking | Medium |
| E. Office and email integration | 23.65–23.69 | Office/Outlook/Teams add-ins via Microsoft Graph; rule-based Exchange archiving; SMTP ingestion | Medium |
| F. Mobile app | 23.95–23.96 | Android/iOS capture and viewing with offline queue | Medium–large |
| G. Dashboards and report designer | 23.34, 23.59–23.60 | Role dashboards; report designer or export to the bank's BI tool | Small–medium |
| H. Records extras | 23.21, 23.50, 23.51, 23.73, 23.77 | File plan, federated search, field-level security, biometric/PKI, GenAI data classes | Medium |

## Quick wins to pull into Phases 1–2 (small effort, already marked in the table)

Three-panel view (23.16), saved searches (23.40), page-by-page viewing (23.23), export/import package with
JSON/XML manifest (23.19), manual record declaration (23.76), bulk import port from legacy JDMS (23.17),
software barcode/QR reading in the pipeline (23.88, moves from Later to Phase 2 if pulled in).

## Not planned or not product

- **CMIS 1.0 (23.20):** not planned; an adapter can be added if a deal requires it.
- **DoD 5015.02 / NRAA Oman / VERS certification (23.80):** a commercial decision with cost and lead time; the records design follows these standards so certification stays possible.
- **Two public-sector references >100M documents (23.100):** not achievable as a new product; bid with a partner or request an exception.
- **Single OEM (23.98):** position JDMS Next as one vendor supplying and supporting the whole stack, with embedded open-source engines.
- **Scanner hardware specs (23.97), perpetual licence (23.99), training, helpdesk, methodology (23.101):** commercial, hardware or services answers.

## Full traceability table

The same data is in `07-rfp-coverage.csv` for filtering.

| Ref | Area | Requirement | Delivered in | Coverage | Notes |
| --- | --- | --- | --- | --- | --- |
| 22.a | Loan documents | Configure varied types of loan documents | PoC (m0–2) | Full | Document types + field definitions |
| 22.b | Loan documents | Integrate with LOS to move applications across departments without physical movement | Phase 1 (m3–8) | Full | Integration API + workflows |
| 22.c | Loan documents | Update, inquire and archive documents from LOS | Phase 1 (m3–8) | Full | Integration API; archive via records in P2 |
| 22.d | Loan documents | Preconfigured document checklist per product | Later (Phase 4+) | Full | New: document checklist module (or in the LOS) |
| 22.e | Loan documents | Match uploaded documents with the checklist | Later (Phase 4+) | Full | Needs checklist module; IDP classification helps |
| 22.f | Loan documents | Document deferral process | Later (Phase 4+) | Full | Checklist item deferral with approval and due date |
| 22.g | Loan documents | Generate loan documents from predefined templates | Later (Phase 4+) | Full | Document generation service (legacy JDMS had DOCX-template certificates) |
| 23.1 | Architecture | Run on Linux and Windows, with and without virtualization | Phase 1 (m3–8) | Partial | Java core runs on both; OpenSearch, ClamAV, Gotenberg are Linux-first (VM or containers on Windows) |
| 23.2 | Architecture | Separate document store; only metadata in database | PoC (m0–2) | Full | Object storage + database |
| 23.3 | Architecture | Open, scalable multi-tier architecture with clustering | Phase 1 (m3–8) | Full | Stateless app tier, clustered stores |
| 23.4 | Architecture | Interoperate with legacy and core systems via open standards | Phase 1 (m3–8) | Full | REST/OpenAPI, OAuth2 |
| 23.5 | Architecture | On-premises hosting with infrastructure sizing | Phase 2 (m9–12) | Full | Sizing guide from PoC load tests |
| 23.6 | Architecture | Modern React frontend with Java backend; works with leading databases, OSes and clouds | Phase 1 (m3–8) | Full | Exact match: React + Java; PostgreSQL and Oracle |
| 23.7 | Architecture | Enterprise DBMS, open-source preferred | PoC (m0–2) | Full | PostgreSQL |
| 23.8 | Architecture | Microservices / container-based deployment | Phase 1 (m3–8) | Full | Containers; modular monolith + worker |
| 23.9 | Architecture | Built on server-side Java / J2EE | PoC (m0–2) | Full | Java 21, Spring (Jakarta EE APIs) |
| 23.10 | Architecture | Format-agnostic repository and archive | PoC (m0–2) | Full | Any file stored as-is |
| 23.11 | Architecture | Repository for images, PDF, Office, email, audio, video, web links, with lifecycle and rule-based purging | Phase 2 (m9–12) | Partial | All stored; preview limited for audio/video/email; purging via disposition |
| 23.12 | Repository | Unlimited folders and sub-folder levels | PoC (m0–2) | Full | Virtual folders |
| 23.13 | Repository | Link related documents, with search in the same screen | Phase 1 (m3–8) | Full |  |
| 23.14 | Repository | Versioning with comments and audit trail | PoC (m0–2) | Full |  |
| 23.15 | Repository | Check-out / check-in locking | PoC (m0–2) | Full |  |
| 23.16 | Repository | Three-panel view (tree, list/viewer, properties) | Phase 1 (m3–8) | Full | Add to UI design brief |
| 23.17 | Repository | Bulk import of images and electronic documents | Phase 2 (m9–12) | Full | Port legacy Excel-driven bulk upload; migration importer |
| 23.18 | Repository | Import images from external sources preserving metadata | Phase 2 (m9–12) | Partial | JDMS migration tool in PoC; generic importer later |
| 23.19 | Repository | Export / import records with metadata in XML or JSON | Phase 2 (m9–12) | Full | Add export package (JSON + XML manifest) |
| 23.20 | Repository | CMIS 1.0 interoperability | Not planned | — | Not planned; optional CMIS adapter later if a deal needs it |
| 23.21 | Repository | Federated search across multiple repositories | Later (Phase 4+) | Full | Connector framework, later |
| 23.22 | Viewer | In-built viewer, no third-party viewer installs | Phase 1 (m3–8) | Full | PDF.js in the browser |
| 23.23 | Viewer | Page-by-page view and download of multi-page documents | Phase 1 (m3–8) | Full | Range requests / page rendering |
| 23.24 | Viewer | Zoom, zoom percentage, rotate | Phase 1 (m3–8) | Full |  |
| 23.25 | Viewer | Zoom lens, invert | Later (Phase 4+) | Full | Viewer enhancements |
| 23.26 | Viewer | Archive and view PDF/A | Phase 2 (m9–12) | Full | PDF/A renditions via Gotenberg |
| 23.27 | Viewer | Thumbnail view including vector PDF | PoC (m0–2) | Full |  |
| 23.28 | Viewer | View in web, desktop and mobile applications | Phase 1 (m3–8) | Partial | Web and responsive mobile web; native apps later |
| 23.29 | Annotation | Annotations: lines, arrows, shapes, text, image stamps, sticky notes, highlights | Later (Phase 4+) | Full | Annotation layer (separate from original) |
| 23.30 | Annotation | Redaction (hide text) securable per user | Later (Phase 4+) | Full | Redacted rendition + access rule |
| 23.31 | Annotation | Auto-stamp annotations with user, date, time | Later (Phase 4+) | Full |  |
| 23.32 | Annotation | Annotations secured for selected users | Later (Phase 4+) | Full |  |
| 23.33 | Annotation | Annotations stored separately; print with or without | Later (Phase 4+) | Full | Design already keeps originals untouched |
| 23.34 | Dashboards | Three role dashboards (knowledge worker, business, senior management) with widgets | Later (Phase 4+) | Partial | Home page with tasks and recent items in P1; role dashboards later |
| 23.35 | Indexing | User-defined indexes on documents (and folders) | PoC (m0–2) | Partial | Documents yes; folder-level indexes later |
| 23.36 | Indexing | Manual and automatic indexing via OCR or other applications | Phase 2 (m9–12) | Full | Manual + API in P1; OCR/IDP extraction in P2 |
| 23.37 | Indexing | Automatic full-text indexing | PoC (m0–2) | Full | Tika + OCR + OpenSearch |
| 23.38 | Indexing | Rubber-band zone OCR mapped to index fields | Later (Phase 4+) | Full | Capture/indexing UI on top of OCR |
| 23.39 | Search | Extensive search on content and profile (name, dates, owner, keywords) | Phase 1 (m3–8) | Full |  |
| 23.40 | Search | Save search queries and results | Phase 1 (m3–8) | Full | Small addition |
| 23.41 | Security | Users, groups and roles | Phase 1 (m3–8) | Full | Keycloak |
| 23.42 | Security | Permissions on folder, document and object level | Phase 1 (m3–8) | Full |  |
| 23.43 | Security | Access levels: delete, edit, view, print, copy, download | Phase 1 (m3–8) | Partial | Print/copy cannot be fully prevented in a browser; controlled preview + watermark |
| 23.44 | Security | Time-bound rights assigned by owners/supervisors, auto-revoked | Phase 1 (m3–8) | Full | Document grants with expiry |
| 23.45 | Security | System privileges (create users, define indexes) | Phase 1 (m3–8) | Full |  |
| 23.46 | Security | Encrypted password storage; password policy admin UI (length, lockout, expiry, history) | Phase 1 (m3–8) | Full | Keycloak policies |
| 23.47 | Security | LDAP / SSO | Phase 1 (m3–8) | Full | Keycloak federation |
| 23.48 | Security | Database-based authentication | Phase 1 (m3–8) | Full | Keycloak local users |
| 23.49 | Security | Two-factor authentication / OTP | Phase 1 (m3–8) | Full | Keycloak MFA |
| 23.50 | Security | Three-factor auth; PKI and biometric integration | Later (Phase 4+) | Partial | PKI client certs possible in Keycloak; biometrics via partner |
| 23.51 | Security | Rights on workflow stages, documents, forms and data fields | Phase 1 (m3–8) | Partial | Stages and documents yes; field-level later |
| 23.52 | Security | SSL/HTTPS and session timeouts | PoC (m0–2) | Full |  |
| 23.53 | Admin | Web-based administration for the whole system | Phase 1 (m3–8) | Full |  |
| 23.54 | Admin | Index structure definition UI | PoC (m0–2) | Full |  |
| 23.55 | Admin | Purge old audit trail; selective logging | Phase 2 (m9–12) | Partial | Purge by retention with approval; security events cannot be switched off (tamper-evident design) |
| 23.56 | Admin | Full and incremental backups; third-party backup integration | Phase 2 (m9–12) | Full | PostgreSQL PITR + object-store replication |
| 23.57 | Admin | Automated local and remote backup; replication for HA | Phase 2 (m9–12) | Full |  |
| 23.58 | Audit | Audit trail of every action at user, folder, cabinet and document level, by action and date range | PoC (m0–2) | Full | Hash-chained audit |
| 23.59 | Reports | Standard reports at document, folder and user level; records and request/return reports | Phase 2 (m9–12) | Partial | Audit query + standard reports |
| 23.60 | Reports | Report designer for new reports | Later (Phase 4+) | Partial | Or export to the bank's BI tool |
| 23.61 | Workflow | Reminders, alarms, alerts for pending and delayed work; delegation | Phase 2 (m9–12) | Partial | SLA flags + delegation in P1; email in P2; SMS later |
| 23.62 | Integration | Fully functional APIs (upload, delete, metadata) usable by bank team without vendor | Phase 1 (m3–8) | Full | OpenAPI docs + client credentials |
| 23.63 | Integration | Invoke external programs from process steps | Phase 2 (m9–12) | Full | Flowable service tasks |
| 23.64 | Integration | XML-based integration | Phase 1 (m3–8) | Partial | JSON REST; legacy XML adapter as stretch |
| 23.65 | Integration | Email server integration | Phase 2 (m9–12) | Partial | SMTP notifications; email ingestion later |
| 23.66 | Integration | Message ingestion via HTTP, FTP, SMTP | Later (Phase 4+) | Partial | HTTP yes; FTP/SMTP ingestion later |
| 23.67 | Integration | Sourcing via web, mobile, desktop, applications, email/fax | Later (Phase 4+) | Partial | Web + API now; mobile app, email, desktop later |
| 23.68 | Office | Plugins for MS Office, Outlook, SharePoint, Teams | Later (Phase 4+) | Full | Office add-ins / Microsoft Graph |
| 23.69 | Office | Rule-based email archiving from Exchange (and Lotus Notes) | Later (Phase 4+) | Partial | Exchange via Graph later; Lotus Notes not planned |
| 23.70 | AI | AI document identification and classification | Phase 3 (m13–18) | Full | IDP in P2/P3 |
| 23.71 | AI | AI auto-population of metadata; capture all fields from documents | Phase 3 (m13–18) | Full | Rule-based English suggestions from text/OCR on upload built early (B-15, ADR-0024); AI extraction still Phase 3 |
| 23.72 | AI | Q&A over single and multiple documents | Phase 3 (m13–18) | Full | RAG, permission-filtered |
| 23.73 | AI | Auto-create data classes via GenAI | Later (Phase 4+) | Full |  |
| 23.74 | Records | Records management component; end-to-end lifecycle; long-term archival | Phase 2 (m9–12) | Full |  |
| 23.75 | Records | Retention, retrieval and destruction policies; disposition schedules managed by authorised users only | Phase 2 (m9–12) | Full |  |
| 23.76 | Records | User declares records at runtime (record filing) | Phase 2 (m9–12) | Full | Add manual declaration |
| 23.77 | Records | File plan record classification | Later (Phase 4+) | Partial | Cabinets/types/retention in P2; formal file plan later |
| 23.78 | Records | Retrieval business rules post-archiving; retrieve on need | Phase 1 (m3–8) | Full | Retrieval requests |
| 23.79 | Records | Unicode and multilingual | PoC (m0–2) | Full | English/Arabic |
| 23.80 | Records | Certified to DoD 5015.02, NRAA Oman, VERS or equivalent | Not planned | — | Not planned; certification is a commercial decision |
| 23.81 | Physical | Track physical location of documents | Phase 1 (m3–8) | Partial | Location + reference fields; retrieval requests |
| 23.82 | Physical | Space management: warehouse capacity, boxes, racks, barcode labels | Later (Phase 4+) | Full | Physical records module |
| 23.83 | Physical | Track file movement between users and locations; courier details; reference numbers | Later (Phase 4+) | Full | Physical records module |
| 23.84 | Physical | Hybrid physical + electronic records | Later (Phase 4+) | Partial | Electronic in P2; physical module later |
| 23.85 | Capture | Integrated centralized and decentralized scanning engine | Later (Phase 4+) | Full | Capture module (scanner SDK partner) |
| 23.86 | Capture | Capture module: processing, validation, index building, enhancement | Later (Phase 4+) | Full |  |
| 23.87 | Capture | Auto separation by barcode, blank page, fixed page, form recognition | Later (Phase 4+) | Full |  |
| 23.88 | Capture | Barcode / QR reading from images | Later (Phase 4+) | Full | Software barcode reading in pipeline |
| 23.89 | Capture | Image enhancement: deskew, rotation, crop, despeckle, punch-hole removal, blank-page removal | Later (Phase 4+) | Full | Pipeline step (e.g. OpenCV-based) |
| 23.90 | Capture | Automatic quality analysis and independent QC service; batch QC, rescan flags, pass/fail stamps | Later (Phase 4+) | Full |  |
| 23.91 | Capture | Multilingual OCR | PoC (m0–2) | Full | Tesseract en/ar; PaddleOCR benchmark |
| 23.92 | Capture | Capture Office, email with attachments, images; native formats; compound email records | Later (Phase 4+) | Partial | Office/images now; email compound records later |
| 23.93 | Capture | Watch-folder import | Later (Phase 4+) | Full |  |
| 23.94 | Capture | Scanning job statistics, batch review rules, digitization status reports | Later (Phase 4+) | Full |  |
| 23.95 | Mobile | Android/iOS app with offline mode for capture and viewing | Later (Phase 4+) | Full | Native mobile app |
| 23.96 | Mobile | Mobile capture features: compression, B/W, auto crop, perspective, geo watermark | Later (Phase 4+) | Full |  |
| 23.97 | Scanning hardware | Scanner specs: paper sizes, weights, optical DPI, output formats, hardware barcode reader, stacking, TWAIN/ISIS driver features | Not software (commercial/hardware) | — | Hardware procurement / partner, not software |
| 23.98 | Commercial | Single OEM for capture, DMS and records | Not software (commercial/hardware) | — | We are one vendor with embedded open-source engines; position accordingly |
| 23.99 | Commercial | Perpetual licence, unlimited users, on-prem to cloud portability | Not software (commercial/hardware) | — | Licensing decision; owned IP makes it possible |
| 23.100 | Commercial | Two public-sector reference sites >100M documents, 5 years | Not software (commercial/hardware) | — | Cannot meet as a new product; partner or exclude |
| 23.101 | Commercial | Training, helpdesk module, Pro-Agile methodology, 12-month implementation | Not software (commercial/hardware) | — | Services and commercial |