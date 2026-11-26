# 0019 — Frontend: sign-in, data access, screens, visual identity and UI tests

- Status: Accepted
- Date: 2026-10-25

## Context

B-08 delivers the PoC screens (spec §8) in React + TypeScript with Ant Design (O-07 default; the scaffold uses the
current Ant Design 6 line, same library), English and Arabic with RTL, keyboard access, no server-built HTML, and UI
that hides what a user cannot do while the backend stays the control.

## Decision

**Sign-in.** OIDC authorization code with PKCE against Keycloak's public `jdms-web` client, with `oidc-client-ts`
(Apache-2.0). A sign-in screen comes first (language choice, then Keycloak). Tokens are kept in `sessionStorage` (they
survive a reload, not the tab) and renewed in the background; API calls send the bearer token. The browser talks
only to the app origin (Vite proxies `/api` in dev and preview; a reverse proxy in production) and Keycloak.

**Data.** TanStack Query over a small `fetch` client: problem+json errors become `ApiError` with the stable `code`,
which the UI translates (`errors.<code>`, falling back to the server's detail). Every write sends a fresh
`Idempotency-Key`. Uploads are multipart with the `metadata` part before the `file` part (ADR-0011).

**Backend additions for the UI** (all authorised and tested in `CatalogIT`):
`GET /cabinets`, `GET /cabinets/{id}/folders`, `GET /document-types?cabinetId=` (the active metadata model with fields
and LOV options, for the dynamic upload form; configuration only, any authenticated user of the tenant);
`GET /me/permissions` (tenant-wide actions decided by the access policy, without audit); `allowedActions` on
`GET /documents/{id}` (document actions decided for the caller); `cabinetId` filter on `GET /documents`. These are
hints for hiding controls; every request is authorised again.

**Screens.** Sign-in, home (my tasks, recent documents), browse (three panels: cabinet and folder tree, list,
properties with thumbnail), upload (dynamic form from the type's fields: text, number, date, yes/no, list;
classification limited to the type's default or higher), document (details, PDF.js viewer, versions with
check-out/check-in of a new version, approval history, audit for `VIEW_AUDIT`, download, delete, restore), task
inbox (approve, reject, return with comment), search (text, CIF, facets, snippets), recycle bin, records (policies,
holds, disposition), administration (document types with fields as proposals, pending changes, access rules) and
audit (filters, chain verification). Routes are lazy-loaded so sign-in and home do not wait for PDF.js.

**Safety in the browser.** No `dangerouslySetInnerHTML` (lint rule); search snippets arrive as `{text, match}`
segments and are rendered as text with `<mark>`. Previews and thumbnails are fetched with the token as blobs; PDFs
are drawn by PDF.js (`pdfjs-dist`, Apache-2.0) on a canvas, never opened by the browser. Classified documents get a
viewer overlay with the user and time: a deterrent only (per-user watermarking of the file itself stays a later item).

**Visual identity** (superseded by ADR-0022 and the UI handoff). A bank registry rather than the default Ant look: lapis frame (`#163A63`), registry blue for
actions (`#2357A6`), brass (`#B08423`) used only to mark the current section and tab, a cool mist background, flat
panels with thin borders and no decorative shadows. Status inks (approved green, pending amber, rejected red) on the
status labels, which sit next to the title in lists; classification labels get heavier with the level. Browser
surfaces (selection, caret, form accents, link underline, scrollbars) are themed from the palette. Forms that do not
need to interrupt (new version, legal hold, new document type) open in place; only an irreversible decision with a
comment uses a dialog. Empty lists say what to do next. Tokens live in `src/theme.ts` and CSS custom properties; CSS
uses logical properties only. A polish pass followed the Impeccable skill (Operate mode) and its detector reports no
findings. Fonts: IBM Plex Sans, and IBM Plex Sans Arabic under `lang=ar` (Plex Sans for Latin text inside Arabic
screens), bundled from `@fontsource` packages so no font CDN is needed on bank networks (weights 400, 500, 600; Latin
and Arabic subsets only). Their licence, OFL-1.1, was approved for fonts on 2026-10-25 and added to CLAUDE.md.

**Tests.** Unit tests (Vitest): translations complete in both languages and every literal key used in the code
exists; snippets render markup as text; dynamic fields render by type and convert values; error codes translate.
Playwright, two suites:
- `npm run e2e` — the production build without a backend: sign-in screen, Arabic RTL, remembered language,
  keyboard focus. Runs in CI.
- `npm run e2e:stack` — journeys against the real local stack (Keycloak, core, worker, dev users): upload with the
  dynamic form; approve, reject and return from the inbox; browse; PDF.js preview after processing; new version;
  search with facets; delete with approval and restore from the recycle bin; an administrator proposes a type with
  fields and a second approves it; hidden actions per user; skip link and keyboard; every screen in Arabic RTL with
  screenshots attached to the report. Needs the stack running, so it is not in the default CI job yet.

**Found by the UI journeys.** Approving a document at the moment the search indexer marked its version indexed failed
with a 500: the approval listener's update lost the optimistic check, and the task service only retried Flowable's
own conflicts. Every concurrent writer of a version (processing results, approval outcomes, indexing) now loads it
with the version row lock (`DocumentVersionRepository.lockForUpdate`), so they queue instead of failing; the task
service also retries an optimistic-lock failure raised inside a process listener (`VersionWriteConcurrencyIT`).

## Consequences

- The dev realm gives `maker1` CONFIDENTIAL clearance (seed document types default to CONFIDENTIAL, and the server
  refuses classifications below a type's default). The token only seeds a new profile (ADR-0007): on a database where
  `maker1` already signed in, a security admin changes the clearance through `PUT /admin/users/{id}/scope` and a
  second one approves it.
- A CI job for `e2e:stack` needs a runner that can start the compose stack and the two applications (the same
  privileged-Docker runner noted for B-06).
- Records, access-rule and audit screens are functional but minimal (lists and the main actions); retrieval requests
  have no backend in the PoC and no screen.
