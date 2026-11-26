# 0023 — Arabic as a per-bank option

- Status: Accepted
- Date: 2026-10-28

## Context

D-04 and O-10 make the screens English and Arabic (RTL) from the start, and B-02 made Arabic names of document types,
fields and list values mandatory. Most banks the product is sold to work in English: for them the language switch,
the Arabic screens and the mandatory Arabic boxes in Admin are noise, and the mandatory names block an administrator
who has no Arabic text to give. Arabic is an added capability, not a requirement.

## Decision

**Each tenant lists the languages its screens offer**: English always, Arabic optional. The list is a new column
`tenant.languages` (`en` or `en,ar`); new tenants start with English only, tenants that existed before keep Arabic, and
the local seed gives BANK1 and BANK2 both (the Arabic UI tests and the manual's Arabic figures use them).

- **Changing it is maker-checker**, like other metadata administration: `GET /api/v1/admin/languages` (ETag = tenant
  version) and `PUT` with `If-Match` propose a change of subject type `TENANT_LANGUAGES` that a second tenant admin
  approves (`ADMIN_METADATA`). English cannot be switched off, nor the tenant's default language. Applying it writes
  `TENANT_LANGUAGES_CHANGED` to the audit trail. The handler lives in the metadata module, which already owns tenant
  administration through maker-checker; the tenant module only stores the value (`TenantLanguages`), because the
  workflow module depends on the tenant module through audit and could not be used from it.
- **`GET /me` returns `languages`.** The web app shows the English / Arabic switch only when Arabic is on, and keeps a
  bank without Arabic in English even when the browser remembered Arabic. The sign-in page, before the bank is known,
  keeps its switch.
- **Arabic names are optional, whether Arabic is on or off.** `name_ar` and both `label_ar` columns may be null; a
  blank Arabic name is stored as null and has the same 200-character limit when given. English names stay required.
  Arabic screens show the English name where there is no Arabic one. With Arabic off, Admin hides the Arabic boxes
  and a change to a type keeps the Arabic names it already had.

## Consequences

- An English-only bank sees no Arabic anywhere after sign-in; switching Arabic on later needs no data change, and
  names added in Arabic then are shown at once.
- A bank that wants every Arabic name filled in has no rule enforcing it; that would be a later per-tenant setting.
- Search, documents and the integration API do not depend on the setting: the catalog still returns both names.
