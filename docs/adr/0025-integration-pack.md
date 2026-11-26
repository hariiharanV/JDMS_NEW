# 0025 — Integration pack for bank systems: OpenAPI description, folder paths, own references (B-18)

- Status: Accepted
- Date: 2026-11-24

## Context

A core banking vendor (FLEXCUBE) needs a formal API description and a guide to connect; the integration API (B-09,
ADR-0016) was documented only in the internal API outline. The product owner's reference flow: a branch officer
attaches a document in the FLEXCUBE customer creation screen, FLEXCUBE files it in a set DMS folder, keeps the DMS
reference and shows the document later. Two things made that flow awkward: folders could be named only by their UUID,
and a document could not be found by the caller's own reference.

## Decision

- **OpenAPI 3 file, written by hand** (`docs/api/jdms-integration-v1.openapi.yaml`), not generated at run time: no
  new library or endpoint in the product, and the description is curated for vendors (behaviour, retries, error
  codes). `IntegrationApiSpecTest` keeps it honest: it fails when the controller's endpoints or the upload request's
  fields differ from the file. Uses SnakeYAML, already on the classpath through Spring Boot (Apache-2.0).
- **`folderPath`** on upload: folder names as staff see them in the type's cabinet (`/Customer onboarding/KYC`),
  resolved by walking the folder tree by exact names; an unknown path, or a path that disagrees with a given
  `folderId`, is 422 `FOLDER_INVALID` before the stream is read. `folderId` keeps working.
- **`externalRef`** as a lookup key of `GET /integration/v1/documents` (index `ix_document_external_ref`, V16). It was
  already stored on upload; tenant scoping and access trimming apply as for the other keys.
- **The DMS reference number is the `documentId`** (UUID) returned by the upload; no second numbering scheme.
- **The guide** (`JDMS-Next-FLEXCUBE-Integration-Guide.docx`, built from `docs/manuals/build/integration.js`) explains
  the client set-up in Keycloak, tokens, the calls, idempotent retries, the error codes and the mapping from the
  legacy JDMS calls, with the customer creation flow as its example.

## Consequences

- Folders are created through the administration API (no screen yet); the guide says so.
- A renamed folder changes its path: callers that file by path must follow renames (or use `folderId`).
- The OpenAPI file covers the integration API only; the web application's API (`/api/v1`) stays internal.
