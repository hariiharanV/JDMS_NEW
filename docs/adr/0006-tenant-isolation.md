# 0006 — Tenant isolation with Hibernate `@TenantId` and a scoped tenant

- Status: Accepted
- Date: 2026-10-24

## Context

B-01 requires that every query is tenant-scoped and that cross-tenant reads and writes are impossible through any
repository (CLAUDE.md rule 4). Options: a Hibernate filter enabled per session, a repository base class adding
`tenant_id` to every method, or Hibernate's discriminator multi-tenancy (`@TenantId`).

## Decision

- Every tenant-owned entity extends `shared.persistence.TenantScopedEntity`, whose `tenant_id` is `@TenantId`.
  Hibernate then adds `tenant_id = ?` to every query on it (derived queries, JPQL, bulk updates, loads by ID),
  sets `tenant_id` on insert and rejects saving another tenant's entity.
- The current tenant lives in a Java 25 `ScopedValue` (`shared.tenant.TenantScope`). `RequestContextBindingFilter`
  binds it from the authenticated request; background work binds the tenant from its payload. A `ScopedValue`
  cannot leak to the next request on a pooled or virtual thread.
- Fail closed: with no tenant bound, the resolver returns the nil UUID, so tenant-scoped data is invisible and
  inserts fail on the `tenant` foreign key. There is no "root" bypass; if one is ever needed it requires an ADR.
- Defence in depth in the schema: references between tenant-owned rows are composite foreign keys on
  `(tenant_id, id)`, so the database rejects a row pointing at another tenant's row.
- Native SQL is not covered by Hibernate's filter. It is allowed only in `infra` adapter classes (ArchUnit rule)
  and must take the tenant as an explicit bound parameter.

## Consequences

- `TenantIsolationIT` exercises every JPA repository found in the application context against two tenants and fails
  if an entity lacks `@TenantId` or a fixture, so new modules are covered automatically.
- `tenant` is the only table without `tenant_id`.
