# 0012 — Authorization module design

- Status: Accepted
- Date: 2026-10-24

## Decision

- **One policy, spec §6 order.** `access.api.AccessPolicy.decide(subject, action, resource)`: tenant boundary → records
  state (legal hold or declared record blocks `EDIT_METADATA`, `CHECK_OUT`, `DELETE`, `DISPOSE`) → explicit DENY
  rule → clearance ≥ classification → ALLOW rule (role or user; scope TENANT, CABINET, DOCUMENT_TYPE, FOLDER incl.
  sub-folders, DOCUMENT; branch constraint OWN / ALL / LIST) → valid document grant. Grants never bypass deny rules or
  clearance. DENY rules apply whatever the branch. Each `Decision` names its reason and the deciding rule or grant.
  The engine (`PolicyEngine`) is pure and unit-tested; `check()` additionally audits denials (`ACCESS_DENIED`) in
  their own transaction.
- **Scope TENANT** is added to the domain model's scope types: tenant-wide role rules express the actor matrix and
  tenant-wide actions (`ADMIN_METADATA`, `ADMIN_ACCESS`, `VIEW_AUDIT`, records actions).
- **Baseline rules.** A tenant without rules gets the PoC actor matrix (spec §3) at startup or onboarding; afterwards
  security admins change rules through maker-checker. The last tenant-wide role rule granting `ADMIN_ACCESS` cannot be
  removed (no lockout). Rows in `access_rule` are always approved; proposals live in `pending_change`.
- **Search filter.** `searchFilter(subject, action)` returns allowed/denied scopes, cleared classifications and granted
  documents; the document list turns it into a JPA predicate (B-07 will turn it into an OpenSearch filter), so totals
  count only permitted documents.
- **Revocation ≤ 60 s.** Rules are cached per tenant for `jdms.access.rule-cache-ttl` (30 s, startup fails above 60 s)
  and evicted on `AccessRulesChanged` after commit; grants, branch scope and clearance are read live.
- **Not found vs forbidden.** A document the caller may not view (`VIEW_METADATA`) is 404 for every operation (its
  existence is not revealed); a viewable document with a forbidden action is 403.
- **Module boundaries.** `access` depends on neither `metadata` nor `identity`: callers pass resource attributes
  (including folder ancestry) and the `ScopeResolver` SPI validates rule scopes. Maker-checker handlers decide who may
  approve with `canDecide` (policy check) and `approvalBlocker` (separation of duties), so `workflow` does not
  depend on `access`.
- **Delegation** (identity): users propose delegating roles they hold themselves (no re-delegation), at most 90 days;
  a security admin who is neither party approves; revocation is immediate. Effective delegated roles are computed at
  authentication and capped by the delegator's current own roles; `RequestContext` carries them separately.
- **Controllers.** Every endpoint has `@PreAuthorize` (ArchUnit rule): tenant-wide actions use
  `@access.can('ACTION')`; document endpoints use `isAuthenticated()` and check the document-level action in the
  service, where the document is loaded.

## Consequences

- Role removal in Keycloak takes effect at token expiry (5 min locally); rule removal within the cache TTL.
- The quarantine exception (security admins may download infected files) also needs `DOWNLOAD` on the document.
