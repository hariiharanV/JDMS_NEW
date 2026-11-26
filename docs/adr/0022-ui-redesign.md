# 0022 — UI redesign: handoff spec and its phases

- Status: Accepted
- Date: 2026-10-26

## Context

The UI/UX design (O-07) produced a redesign handoff: `docs/handoff/JDMS-Next-UI-Handoff.md`, with reference HTML and
screenshots per screen in `docs/handoff/designs/`. It keeps Ant Design 6, the fonts, the routes, the API and every
permission rule, and changes layout, hierarchy, wording and the visual language. It is delivered in six phases, one
review each (handoff §8). This record covers phase 1 (foundations) and the choices made where the handoff left room.

## Decision

**The handoff is the UI specification.** It supersedes the "Visual identity" paragraph of ADR-0019: one navy frame
(`#13294B`), action blue (`#1D4E89`), gold only for "you are here" (wordmark, active tab ink bar, focus ring), dark
status inks on tinted grounds. Tokens live in `src/theme.ts` (antd) and `src/index.css` (`--jdms-*`), as before.

**Phase 1 delivers:** the tokens; the app shell (`AppShell`: navy header with wordmark, bank, global search, language,
notifications and the user; a grouped side menu; skip link; `<main id="main">`); one set of formatters
(`i18n/format.ts`: `25 Oct 2026, 22:31`, the current year dropped in lists, Western digits in both languages, decision
D2); and the shared components `StatusChip`, `ClassificationLabel`, `PageHeader`, `EmptyState`, `InlineConfirm` and
`Code`. Existing screens use the shell, the page header, the chips and the labels; their own layouts change in
phases 2 to 4.

**Choices where the handoff left room:**

- **The menu follows permissions, not role names.** `buildNav` takes the tenant-wide actions from
  `GET /me/permissions` (the access policy's answer), so a bank that changes what a role may do changes the menu with
  it. With the baseline rules it gives exactly the handoff's role table (unit-tested per role).
- **The side menu is a `<nav>` list of links inside `Layout.Sider`, not an antd `Menu`.** `Menu` renders
  `role="menu"`/`menuitem`, which is for application menus; router links inside menu items are nested interactive
  controls (an axe "serious" finding, against handoff §5), and every entry would take two tab stops. The list uses the
  handoff's Menu token values (40 px items, radius 8, selected `#E8EEF7` with navy text, 12 px group titles).
- **Administration is three routes:** `/admin/types`, `/admin/changes`, `/admin/access` (one menu entry each);
  `/admin` opens the first one the caller may use. Pending changes appear under Admin, or under Security for a
  security administrator who does not also administer types.
- **Notifications use the existing API** (`GET /me/notifications`, `POST /me/notifications/{id}/read`): the bell
  shows the unread count; the panel lists them, opens the document and marks items read. No backend change.
- **Search reads its query from the URL** (`/search?q=`), so the header search works from every screen. The rest of
  the Search redesign is phase 3.
- **Classification in lists** is shown only above the document type's minimum. The minimum comes from
  `GET /document-types` (already loaded for type names), so lists can apply the rule now; gap G3 remains only for
  results whose type the caller cannot read.
- **Focus ring:** antd's own focus styles have higher specificity than a global `:focus-visible`, so the gold ring is
  declared `!important` once in `index.css`; text fields show it on their frame rather than on the inner input.
- **Phone width:** below `lg` the side menu folds away behind a Menu button in the header and opens over the page;
  following a link or pressing Escape closes it, and folded links are `inert`.
- **Open decisions keep the current behaviour:** the home greeting stays "Welcome, {name}" (D1), Arabic wording is
  the handoff's pending the bank's review (D3), Recycle bin stays a top-level item for records officers (D4).

## Phase 2: daily work

Home, Tasks and the document page follow handoff §6.1–6.3, built from new shared pieces: `StatCard`, `FactList`
(a `<dl>` grid), `ApprovalTimeline`, `DocumentViewer` (the PDF.js canvas in a header bar and a sunken well, with the
repeated diagonal watermark), `DocumentPreview` (why there is no preview: no right, processing, quarantined, failed)
and `DecisionBar`.

- **Tasks are a split view** (`/tasks/:taskId`): the list beside the task, which shows the preview and the document's
  facts next to the decision. Decisions happen in place; Reject and Return without a comment stop at the comment
  field; the next task opens after a decision. The confirm dialog and the drawer are gone. On a wide screen the first
  task opens on its own; below `lg` the list and the task take turns, with a back link. Arrow keys move through the
  list. `/tasks?task=…` links still work.
- **The inbox loads 200 tasks a page** (the API maximum) so the filters can count on the client; more come with
  "Show more". The filters are the groups present (uploads, new versions, returned, administration) plus due soon and
  overdue.
- **Home's counts:** makers see Returned to you (their AMEND tasks) and the status facet of search (G11);
  checkers see Waiting for you and Due soon. The tiles open the filtered inbox (`/tasks?filter=`) or search
  (`/search?status=`); search also takes `?cif=`, which the document's CIF links use.
- **Document actions by state** (`documentActions`, unit-tested): one primary action, Delete and Copy link under
  More, delete confirmed in place (`InlineConfirm`). Delete is offered only on active documents not on hold, as in
  the handoff's table. Check out, check in and cancel check-out are on the page.
- **The timeline is an ordered list**, not antd `Timeline` (in antd 6 a Steps variant whose title and content
  slots do not fit a verb, a name, a time and a comment); it keeps the handoff's dots and colours.
- **The canvas is drawn left to right** in Arabic too: a canvas inherits the page direction and PDF.js places glyphs
  assuming LTR.
- **Deferred:** "Edit and resubmit" for a maker's draft (no metadata editing screen exists yet; the maker resubmits
  from the task, after a new version if needed). The manual's screenshots and task wording are refreshed after the
  redesign phases (`npm run screenshots:manual`).

## Phase 3: capture and find

- **Upload** is four numbered sections (file, what is it, customer and the type's fields, filing and access) beside
  a sticky "Before you submit" rail that counts the required fields still empty. Field types map as the handoff says
  (yes/no is a choice of two, not a switch; long lists can be searched; long texts count characters); dates read as
  "1 Jun 2021" and also accept 2021-06-01 when typed. The title defaults to "{Type} {customer name}" until the user
  edits it; the server's field violations appear on the fields. Opened from Browse with `?cabinet=&folder=`.
- **Search** keeps its whole view in the URL (`q`, `scope`, `status`, `type`, `cabinet`, `branch`, `from`, `to`,
  `page`, `view`). The CIF box is replaced by the Everything/CIF scope; a one-word search that finds nothing offers
  "Search … as a CIF instead". Results are cards with labelled facts, highlights as `<mark>`, and a Preview that opens
  in place under the result (one at a time); a table view uses the Browse columns.
- **Browse** is `/browse/:cabinet?/:folderId?`: the cabinet and its folders, the documents (title with type, CIF,
  status, updated) and the selected document's properties with Open and Download. Below 1100 px the properties
  pane is left out and choosing a row opens the document.
- **dayjs** (MIT) is declared as a direct dependency at the version antd already brings, for the date range filter.

## Phase 4: governance

- **Records** is one page of three sections (legal holds, retention policies, disposition) under counts that jump
  to them. New holds and policies open in place; releasing a hold and approving a disposition batch are the only
  dialogs, because neither can be undone; approving asks for the document count to be typed. Rejecting a batch opens
  a comment on its row. Policy periods read as years and months.
- **Recycle bin:** title with type, CIF, branch, deleted time and Restore per row, with an empty state.
- **Audit:** the chain's state as a banner (intact, broken, verifying) with Verify now; filters that apply as they
  change, debounced, and live in the URL (by, on, action with display names, period, outcome); events with display
  names and their codes, expandable to the full IDs, the chain link and the details.
- **Pending changes:** Approve acts directly; Reject opens a comment on the row; your own proposals read "Waiting
  for another administrator".
- **Access rules:** the whole-bank role rules as a role × action matrix (grouped columns, 24 px cells, a sticky role
  column, deny wins, unknown actions in "Other"), unit-tested against the baseline table; a cell opens the rules
  behind it under the matrix; narrower rules are listed below; a rule proposal opens in place (prefilled from a
  cell).
- **Document types:** a filterable list (test types with the `E2E_` prefix hidden by default) beside the selected
  type's facts, fields and rules (`/admin/types/:code`); new types and proposed changes are pages of their own
  (`/admin/types/new`, `/admin/types/:code/propose`). A change to a type's fields is a second proposal, because the
  API takes the fields separately.
- **Playwright** configs read optional extra Chromium flags from `JDMS_E2E_CHROMIUM_ARGS`, for a machine whose
  software renderer hangs on screenshots (it happened on the build machine during this phase:
  `--disable-gpu --disable-software-rasterizer` works around it). Nothing changes when it is unset.

## Phase 5: sign-in

- A Keycloak **login theme** `jdms` (`deploy/keycloak/themes/jdms/login/`, parent `keycloak.v2`): only
  `template.ftl` is overridden, with the split layout of handoff §6.11 (navy brand panel with the wordmark, an
  environment tag, the bilingual headline and three points; the form panel with the bank name, an English/العربية
  switch, "Sign in", the hint and the help line). Every page `keycloak.v2` renders (password reset, errors, required
  actions) keeps working inside the frame, and the field IDs the journeys use are the parent's.
- IBM Plex is served from the theme (the @fontsource files, OFL-1.1, with `fonts/OFL.txt`): the sign-in page loads
  nothing from outside the bank. The gold focus ring, logical CSS and the reduced-motion rule match the app.
- `docker-compose.yml` mounts `themes/`; the realm import sets `"loginTheme": "jdms"`; `JDMS_ENV_LABEL` sets the tag
  ("LOCAL DEVELOPMENT" by default, empty for none) through `${env.JDMS_ENV_LABEL:}` in `theme.properties`. A
  Keycloak without the theme falls back to its built-in one (`KeycloakIntegrationIT` still passes).
- The help desk named in the help line is a message property (`jdmsHelpDesk`), which a bank overrides.

## Phase 6: polish

- **axe** (`@axe-core/playwright` 4.13.0, MPL-2.0, a test-only dependency) scans every screen in English and Arabic
  at 1440 px and in English at 390 px (`e2e/stack/accessibility.spec.ts`): WCAG 2.2 A and AA, no serious or critical
  violation. At 390 px the same journey checks that no page scrolls sideways. Fixed on the way: placeholder contrast
  (the antd token, 1.8:1 → the secondary ink), 24 px targets for the clear buttons in fields, `aria-required` on
  checkbox groups (`CheckboxGroupField`), the user button's name at phone width, and keyboard access to tables that
  scroll sideways without anything focusable in them (`TableScroll`).
- **Icons** are hidden from assistive technology everywhere: a button reads "Preview", not "eye Preview"; icon-only
  buttons carry their own label.
- **Reduced motion** is checked: with the preference set, no button, link or card transitions or animates.
- **Arabic copy:** the maker is "المُنشئ" throughout (it was also "المُدخِل" in two places). The final wording is the
  bank's Arabic reviewer's (decision D3).

**Handoff §5 checklist:** skip link (journey "keyboard"), real controls only and named icon buttons (axe), contrast
4.5:1 (axe), status and classification never colour alone (chips with shapes, labels with icons and weight), gold
focus ring (journey "keyboard"), reduced motion (journey), tab order in LTR and RTL (journeys "keyboard" and
"Arabic"), antd ARIA and matrix row headers (axe; `<th scope="row">`), text expansion (Arabic at 1440 and 390 px,
buttons and chips wrap), both languages at 1440 and 390 px (axe journeys and the Arabic journey).

## Consequences

- Backend gaps are marked in the code as `TODO(backend-gap: Gx)` (handoff §9); in phase 1: G1 (bank display name,
  the tenant code stands in) and G2 (branch names, codes stand in). Phase 2 uses G4, G10 and G11 of the handoff and
  adds:

  | ID | Needed by | Gap | Fallback |
  |---|---|---|---|
  | G12 | Tasks list | The maker's name on each task ("From …") | "Received {time}"; the task pane names the submitter from its history |
  | G13 | Document facts | The retention policy that applies to a document | Left out |
  | G14 | Document page | The legal hold's name | "On legal hold" without the name |
  | G15 | Document page | Who has a document checked out, and since when | "Another user has this document checked out" |
  | G16 | Home (checker) | "Returned by you": a count of the checker's own returns | Tile left out |

  Phase 3 uses G5 and adds:

  | ID | Needed by | Gap | Fallback |
  |---|---|---|---|
  | G17 | Search facets | Several values of one facet combined with OR | One value per facet (the facet counts follow the filter, so the others drop out) |
  | G18 | Search | Sort order (best match, newest, title) | The API's order: best match, then newest; no sort control |
  | G19 | Upload | Whether the type needs approval, and its route, in the catalogue | A general sentence; the button says "Upload" |
  | G20 | Search | The current version (for a thumbnail) in search hits | A file icon |
  | G21 | Browse | Title filter, sorting and per-folder counts in the document list | CIF filter only; no sort; no counts |

  Phase 4 uses G6–G10 and adds:

  | ID | Needed by | Gap | Fallback |
  |---|---|---|---|
  | G22 | Records | A comment when approving a disposition batch | The dialog asks only for the typed document count |
  | G23 | Records | A reason when releasing a legal hold | The dialog states the consequence; no reason field |
  | G24 | Records | Who prepared a batch | No "Waiting for a second records officer" for the preparer |
  | G25 | Audit | One "who or what" search over names and IDs | Two exact-ID filters: by and on |

- Playwright journeys reach administration through the side menu, and the skip link reads "Skip to main content".
  From phase 2 they decide tasks in the split view, find the preview on Overview and delete through More.
- All six phases are delivered. Still open: the product owner's decisions D1 (greeting; "Welcome" kept), D2
  (digits in Arabic; Western used), D3 (Arabic wording), D4 (Recycle bin stays a top-level item) and D5 (watermark
  levels; confidential and above, as before), and backend gaps G1–G25. The user manual's screenshots and steps describe the earlier screens; they
  are retaken with `npm run screenshots:manual` and the text updated when the manual is next rebuilt.
