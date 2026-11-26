# Architecture decision records

One file per decision: `NNNN-short-title.md`, numbered in order. Status is `Accepted`, `Superseded by NNNN`
or `Proposed`. Record the context, the decision and its consequences; keep each record short.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-java-25.md) | Target Java 25 instead of Java 21 | Accepted |
| [0002](0002-gitlab-ci.md) | Self-hosted GitLab and GitLab CI | Accepted |
| [0003](0003-seaweedfs-dev-object-store.md) | SeaweedFS as the S3 store for local development and tests | Accepted |
| [0004](0004-identifier-renames.md) | Rename identifiers that break the portability rules | Accepted |
| [0005](0005-clamav-gpl-network-service.md) | ClamAV (GPL-2.0) is used only as a separate network service | Accepted |
| [0006](0006-tenant-isolation.md) | Tenant isolation with Hibernate `@TenantId` and a scoped tenant | Accepted |
| [0007](0007-identity-attribute-authority.md) | Which system owns which identity attribute | Accepted |
| [0008](0008-audit-trail-started-in-b01.md) | Audit recording starts in B-01 | Accepted |
| [0009](0009-maker-checker-for-admin-changes.md) | Maker-checker for administrative changes | Accepted |
| [0010](0010-metadata-model-choices.md) | Metadata model choices made in B-02 | Accepted |
| [0011](0011-content-storage-and-encryption.md) | Content storage, encryption and streaming upload | Accepted |
| [0012](0012-authorization-module.md) | Authorization module design | Accepted |
| [0013](0013-approval-workflows-flowable.md) | Approval workflows on embedded Flowable | Accepted |
| [0014](0014-processing-pipeline-and-platform-module.md) | Processing pipeline and the shared platform module | Accepted |
| [0015](0015-search-and-query-time-access.md) | Search index and query-time access filtering | Accepted |
| [0016](0016-integration-api.md) | Integration API for bank systems | Accepted |
| [0017](0017-records-management.md) | Records: deletion, holds, retention and disposition | Accepted |
| [0018](0018-audit-trail-completion.md) | Audit trail completion: database grants, verification, queries, coverage | Accepted |
| [0019](0019-frontend.md) | Frontend: sign-in, data access, screens, visual identity and UI tests | Accepted |
| [0020](0020-legacy-migration.md) | Legacy migration: import API in the core, decryption only in the tool, reconciliation per run | Accepted |
| [0021](0021-performance-tooling.md) | Performance, resilience and OCR tooling (B-13) | Accepted |
| [0022](0022-ui-redesign.md) | UI redesign: handoff spec and its phases | Accepted |
| [0023](0023-optional-arabic.md) | Arabic as a per-bank option; Arabic names optional | Accepted |
| [0024](0024-assisted-indexing.md) | Assisted indexing: upload fields suggested from the file (B-15) | Accepted |
| [0025](0025-integration-pack.md) | Integration pack for bank systems: OpenAPI description, folder paths, own references (B-18) | Accepted |
