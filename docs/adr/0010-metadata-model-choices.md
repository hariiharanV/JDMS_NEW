# 0010 — Metadata model choices made in B-02

- Status: Accepted
- Date: 2026-10-24

| Topic | Decision | Why |
| --- | --- | --- |
| Document type codes | Unique per **tenant**, not per cabinet | The integration API (`04-api-outline.md`) names a type by `documentTypeCode` only. Migration: legacy types with the same code in two cabinets get a cabinet prefix (B-12) |
| Mandatory standard fields | New `document_type.required_standard_fields` (JSON list of standard field names, e.g. `["cif","expiry_date"]`) | Legacy `DMS_DIRECTORY_MAINTENANCE.mandatory_fields`; expiry date and banking keys are document columns, not custom fields. Enforced on upload in B-03 |
| Field changes | `PUT /admin/document-types/{id}/fields` replaces the whole ordered list in one approval; fields are matched by key | The UI edits the form as a whole; one approval per form change. Removed fields stay in existing documents' JSON but are no longer shown or validated |
| Field keys | `[a-z][a-z0-9_]{0,49}`, never a standard or reserved document field name | Avoids two meanings for one name in search and exports |
| Facets | Only searchable LOV, BOOLEAN or TEXT fields; never sensitive fields | Facet counts would reveal sensitive values; numbers and dates are range filters |
| Custom values | NUMBER as decimal (≤ 38 digits, portable to Oracle `NUMBER`), DATE as ISO `yyyy-MM-dd`, BOOLEAN, LOV as an active value code; TEXT without control characters; unknown keys rejected; defaults applied | One normal form for storage, search and integration; messages never echo values |
| Deactivate vs delete | Cabinets and document types are deactivated; LOV values left out of an update are deactivated; only empty folders and unused LOVs are deleted | Documents and history refer to them |
| Folders | Admin-managed with maker-checker; sibling names unique ignoring case; depth ≤ 20; `path_cache` display only | Spec §4.1 groups folders with the admin model; legacy folders were admin-defined |
| Approval process keys | `single-approval`, `multi-stage-approval`, `committee-approval` (or none) | The three PoC processes (§4.4), deployed in B-05 |
