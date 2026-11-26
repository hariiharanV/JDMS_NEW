# 0016 — Integration API for bank systems

- Status: Accepted
- Date: 2026-10-25

## Decision

- **Where it lives.** `/integration/v1/documents` is a second web face of the document module (it serves the same
  documents through the same domain services and access checks); the `integration` module adds what is specific to
  bank-system callers: a per-client rate limit and an `INTEGRATION_CALL` audit event per request (method, route,
  status, client). No separate domain logic, so no module boundary is crossed.
- **Clients only, identity from the token.** Only OAuth2 clients (service accounts) may call it (403 for people,
  who use `/api/v1`). Permissions come from the client's roles like any user's (D-09). Legacy header fields that
  named a user or role (`user_id`, `role_id`, also camel case) are refused with 422 `FIELD_NOT_ALLOWED`, never
  ignored silently, so a migrated caller learns at once that impersonation is gone.
- **Legacy mapping** (06 §4) in the request record: `customerNo` is accepted as the CIF (422 if both differ);
  `issueDate` fills the type's `issue_date` field when the type has one; `externalSystem` (required) is recorded as
  the source (`INTEGRATION:<client>:<system>`) and is part of the idempotency hash; the title defaults to the type and
  the main business key. Listing requires at least one business key.
- **New version in one call** (`POST /{id}/versions`): check-out and check-in in one idempotent operation for
  callers that never hold locks; refused while another caller has the document checked out.
- **Rate limit**: token bucket per client and node (`jdms.integration.rate-limit.requests-per-second`, `.burst`;
  defaults 20 and 40), 429 `RATE_LIMITED` with `Retry-After`. A cluster divides the limit by its size.
- **Outages**: object-store failures (connection, timeouts, 5xx) are 503 `STORAGE_UNAVAILABLE` (nothing stored;
  repeat with the same `Idempotency-Key`); S3 timeouts are configurable (`jdms.storage.timeout`). Search outages do
  not affect uploads: indexing after commit fails over to `INDEX` jobs and catches up (ADR-0015).

## Consequences

- The thin adapter for the legacy XML/JSON contract (stretch goal) is not built; O-03 should first confirm which
  FLEXCUBE callers move and with which payloads.
- Soft delete through the integration API arrives with B-10.
