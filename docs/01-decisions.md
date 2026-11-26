# 01 — Decisions

Status as of 24 Oct 2026. "Decided" items are fixed for the PoC. "Open" items have a default so work is
not blocked; Claude Code uses the default until the owner decides, and records any dependency in the backlog.

## Decided

| ID | Decision | Notes |
| --- | --- | --- |
| D-01 | Build JDMS Next as a new core with open-source engines (option C), starting with a time-boxed PoC | Build vs buy is re-confirmed at the end of Phase 0 against Alfresco Community and one commercial platform |
| D-02 | Java 25, Spring Boot (current GA, pinned), Spring Modulith modular monolith | Separate `jdms-worker` app for file processing. Java 21 → 25 per ADR-0001 |
| D-03 | Maven multi-module build | Team standard |
| D-04 | React + TypeScript (Vite) frontend | English and Arabic, RTL-ready from the start |
| D-05 | PostgreSQL for development and the PoC; Oracle added after the PoC and before the pilot bank | Portability rules in `CLAUDE.md` |
| D-06 | Authorization as an in-process Java module behind an `AccessPolicy` interface | Can be replaced by OPA or Cedar later without changing callers |
| D-07 | Flowable (BPMN + DMN), embedded | Replaces the legacy workflow tables and runtime-compiled rules |
| D-08 | OpenSearch for full-text search, with ACL filtering at query time | Vectors deferred (AI is out of PoC scope) |
| D-09 | Keycloak for identity (OIDC); OAuth2 client credentials for integrations | Legacy JDMS trusted user/role IDs sent by the caller; this is removed |
| D-10 | Transactional outbox (Spring Modulith) + `job` table with `SKIP LOCKED`; no Kafka | Revisit only with measured need |
| D-11 | File processing: ClamAV, Apache Tika, Gotenberg, Tesseract; PaddleOCR in the OCR benchmark | All in `jdms-worker` |
| D-12 | Files via the S3 API behind `BlobStore`; envelope encryption behind `KeyService` | Local KMS stub in the PoC |
| D-13 | Tenant-safe from the first release: `tenant_id` on every row, blob key prefix, index filter, cache key, job and audit event | Single-tenant deployments still carry a tenant ID |
| D-14 | Base package `com.jmr.jdms`; product name "JDMS Next" | Change before first release if marketing decides otherwise |
| D-15 | Source control and CI on the self-hosted GitLab (`git@192.168.1.181:Atul.Arvind/jdms_next.git`), pipeline in `.gitlab-ci.yml` | Closes O-09; ADR-0002 |
| D-16 | SeaweedFS (Apache-2.0) is the S3 store for local development and Testcontainers tests | MinIO OSS archived; ADR-0003. Production store is still O-01 |

## Open — need a decision from the product owner

| ID | Question | Default until decided | Needed before |
| --- | --- | --- | --- |
| O-01 | Object storage product for production (on-premises and cloud) | Dev: SeaweedFS (D-16). Candidates to test: Ceph RGW, NetApp StorageGRID, Dell ECS/ObjectScale, in-country cloud S3 | Backlog item B-10 (records and WORM tests) |
| O-02 | Pilot bank and a sample document set (anonymised scans, statements, IDs, Arabic and English) | Synthetic generated documents | B-06 OCR benchmark, B-12 migration rehearsal |
| O-03 | Which FLEXCUBE-side integration the PoC proves (current JDMS `/document-upload`, `/document-download`, `/document-details` callers), with sample payloads and a test environment | Build the integration API from the legacy contract in `06-legacy-jdms-reference.md` and test with a mock caller | B-09 |
| O-04 | Access to a copy of a legacy JDMS database and file store, plus the legacy AES secret via the security owner | Synthetic legacy fixtures encrypted with a test key | B-12 |
| O-05 | Named owners for the legacy security workstream (strategy doc) | Not part of this repo | Gate 1 |
| O-06 | Confirm non-functional targets (strategy doc proposals) | Use the proposed targets for test thresholds | B-13 |
| O-07 | UI component library and design system (UI/UX team) | Ant Design v5 (strong tables, forms and RTL support) | B-08 |
| O-08 | Classification levels | `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED` | B-04 |
| O-09 | ~~Source control and CI platform~~ | Decided: D-15 (self-hosted GitLab) | B-00 |
| O-10 | Arabic UI coverage in the PoC | All screens RTL-ready; Arabic translations for login, browse, upload and search | B-08 |
