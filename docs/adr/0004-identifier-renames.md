# 0004 — Rename identifiers that break the portability rules

- Status: Accepted
- Date: 2026-10-24

## Context

`CLAUDE.md` requires identifiers of 30 characters or fewer and no reserved words, so the schema can move
to Oracle after the PoC. Three names in `docs/03-domain-model.md` break these rules:

| Table | Name in the domain model | Problem |
| --- | --- | --- |
| `document_type` | `metadata_change_requires_approval` | 33 characters |
| `retention_policy` | `trigger` | Oracle reserved word |
| `document_version`, `approval_record` | `comment` | Oracle reserved word |

## Decision

| Table | New column name |
| --- | --- |
| `document_type` | `meta_change_needs_approval` |
| `retention_policy` | `trigger_type` |
| `document_version` | `version_comment` |
| `approval_record` | `decision_comment` |

API field names are unaffected (`comment` stays `comment` in JSON). `docs/03-domain-model.md` is updated.

## Consequences

Future names are checked against both PostgreSQL and Oracle reserved-word lists when tables are added.
