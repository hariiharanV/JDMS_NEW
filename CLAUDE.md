# JDMS Next — instructions for Claude Code

JDMS Next is a bank-grade document management system (DMS) that replaces the legacy JMR DMS ("JDMS").
It is a commercial product sold to banks, most of which run Oracle FLEXCUBE core banking.
We are in **Phase 0: time-boxed proof of concept (PoC)**. The goal is evidence for an architecture
decision, built with production-quality code, not throwaway code.

## Read these first (in order)

1. `docs/01-decisions.md` — what is decided, what is open, and the defaults to use when a decision is open
2. `docs/02-poc-functional-spec.md` — what the PoC must do, and how it should behave
3. `docs/03-domain-model.md` — entities, fields, states and relationships
4. `docs/04-api-outline.md` — REST API contracts and conventions
5. `docs/05-backlog.md` — ordered work items with done criteria. **Work top to bottom.**
6. `docs/06-legacy-jdms-reference.md` — how the legacy system works; for behaviour, mapping and migration only
7. `docs/07-rfp-coverage.md` — a bank RFP mapped to the roadmap. Context only: do not build later-phase items, but avoid designs that would block them (e.g. keep originals untouched so annotations can be layered later)

The strategy and architecture rationale lives in the "JDMS Modernisation — DMS Research, Comparison &
Recommended Approach" doc (Claude Docs). If this pack and that doc disagree, this pack wins for build
details; raise the conflict in `docs/adr/`.

## Stack (pin exact versions in the build; use the current supported GA line at project start)

- Backend: Java 25 (ADR-0001), Spring Boot (current GA), Spring Modulith, Spring Security (OAuth2 resource server), Spring Data JPA/Hibernate, Flyway, Maven
- Workflow: Flowable (BPMN + DMN), embedded in the core app
- Database: **PostgreSQL** during the PoC. Oracle is added after the PoC; follow the portability rules below
- Search: OpenSearch
- Files: S3 API (AWS SDK v2) behind a `BlobStore` interface; SeaweedFS for local dev and tests (ADR-0003)
- Processing: ClamAV (antivirus), Apache Tika (text extraction), Gotenberg (PDF renditions), Tesseract OCR (PaddleOCR benchmarked later)
- Identity: Keycloak (OIDC); integration clients use OAuth2 client credentials
- Frontend: React + TypeScript (Vite), i18n English/Arabic with RTL support
- Tests: JUnit 5, Testcontainers (real PostgreSQL, OpenSearch, Keycloak, S3), Spring Modulith `verify()`, ArchUnit, Playwright for UI
- Jobs/events: Spring Modulith event publication registry (transactional outbox) + a `job` table polled with `SELECT … FOR UPDATE SKIP LOCKED`. **No Kafka** in the PoC

## Repository layout

```
jdms-next/
  CLAUDE.md
  docs/                      build pack + docs/adr/ (architecture decision records)
  backend/
    pom.xml                  parent POM (dependency management, plugins, versions)
    jdms-core/               Spring Boot app: API, domain modules, Flowable
      src/main/java/com/jmr/jdms/
        tenant/  identity/  metadata/  document/  access/  workflow/
        records/  audit/  search/  integration/  shared/
      src/main/resources/db/migration/postgresql/   Flyway scripts
      src/main/resources/db/migration/oracle/       empty until after PoC (keep the folder)
    jdms-worker/             Spring Boot app: antivirus, extraction, renditions, OCR, indexing
    jdms-migration/          CLI tool: legacy JDMS -> JDMS Next
    jdms-migration-e2e/      tests only: the tool against a legacy fixture and the real core, in-process (ADR-0020)
    jdms-contracts/          event and job payload records shared by core and worker (no logic)
    jdms-platform/           infrastructure shared by core and worker: encrypted blob storage, job queue (ADR-0014)
    jdms-perf/               test tooling only: synthetic data generator, Gatling load tests (ADR-0021)
  frontend/                  React + TypeScript (Vite)
  perf/                      OCR benchmark image, resilience rehearsal, backup and restore scripts (ADR-0021)
  deploy/
    docker-compose.yml       postgres, keycloak, opensearch, seaweedfs (s3), clamav, tika, gotenberg
    keycloak/                realm export for local dev
  .gitlab-ci.yml             CI pipeline (self-hosted GitLab, ADR-0002)
```

Each folder under `com.jmr.jdms` is a Spring Modulith application module. Modules talk to each other
only through their public API package (`<module>/api`) or domain events. `shared/` holds only
cross-cutting primitives (IDs, tenant context, errors). `ApplicationModules.of(...).verify()` must pass in CI.

## Commands

- `cp deploy/.env.example deploy/.env` — once; local secrets (git-ignored), replace every `CHANGE_ME`
- `docker compose -f deploy/docker-compose.yml up -d --wait` — start local dependencies and wait until healthy
- `cd backend && ./mvnw verify` — build, unit + integration tests (Testcontainers), module verification, SBOM, licence check (JDK 25)
- `set -a; . deploy/.env; set +a; cd backend/jdms-core && ../mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- `set -a; . deploy/.env; set +a; cd backend/jdms-worker && ../mvnw spring-boot:run -Dspring-boot.run.profiles=local` (processing; after the core has migrated the database)
- `cd frontend && npm ci && npm run dev` (port 5180) / `npm run lint` / `npm run typecheck` / `npm test` / `npm run build` / `npm run e2e`
- `java -jar backend/jdms-migration/target/jdms-migration-*-exec.jar catalog|run [--jdms.migration.since=<instant>]` — legacy migration (settings in `jdms-migration/src/main/resources/migration.yml`, secrets from the environment; ADR-0020)
- `set -a; . deploy/.env; set +a; cd frontend && npm run e2e:stack` — UI journeys against the running local stack (compose, core and worker started as above)
- `cd frontend && npm run licence-check` / `npm run sbom` — production licence check and CycloneDX SBOM
- `java -jar backend/jdms-perf/target/jdms-perf-*-exec.jar generate --jdms.perf.documents=1000000` — synthetic tenant PERF for performance tests; `verify-content --sample=500` checks stored content against its SHA-256 (ADR-0021)
- `cd backend && ./mvnw -pl jdms-perf gatling:test -Dgatling.simulationClass=com.jmr.jdms.perf.load.SearchSimulation` — load tests (also `IngestSimulation`, `PreviewSimulation`, `RevocationSimulation`; options in docs/perf/README.md)
- `deploy/tls/make-local-cert.sh <ip-or-host>` — open the local stack to other machines over HTTPS (local certificate authority; settings it prints go in `deploy/.env`; deploy/tls/README.md)
- `docs/manuals/build/build.sh` — rebuild the user manual, testing and installation guides (Word) and the executive briefing (PowerPoint); `cd frontend && SHOTS=../docs/manuals/screenshots npm run screenshots:manual` retakes their screenshots against the local stack (docs/manuals/build/README.md)
- `set -a; . deploy/.env; set +a; cd frontend && npm run seed:test-data` — test customers and documents in tenant BANK1 of a fresh stack (at `JDMS_WEB_PUBLIC_URL`; branch 002 needs `JDMS_INTEGRATION_TEST_CLIENT_SECRET`); a second run adds nothing (docs/manuals/build/README.md)
- `python3 perf/resilience/rehearsal.py <out-dir>` / `perf/backup/backup.sh <dir>` / `perf/backup/restore.sh <dir>` — resilience and backup rehearsals against the local stack (docs/perf/README.md)

Keep this section accurate when you add or change commands.

## Non-negotiable security rules

1. No secrets in code, config files, logs, tests or commit history. Use environment variables or a secret store; `.env` files are git-ignored and only `.env.example` is committed.
2. Every SQL/JPQL statement uses bound parameters. No string concatenation of user input into queries, ever.
3. Every endpoint, download, preview, search hit, facet count, export and job result goes through the `access` module's `AccessPolicy`. No endpoint without an authorization test (allowed and denied cases).
4. Every query is tenant-scoped. Cross-tenant access must be impossible, and tests prove it.
5. Never use filesystem paths or filenames as identifiers. Documents and versions have UUIDs; blob keys are internal.
6. Uploaded files are untrusted: antivirus and parsing run only in `jdms-worker`, with size and time limits. The core never parses file content.
7. Encryption: envelope encryption (per-object data key wrapped by a per-tenant key). In the PoC the tenant key comes from a local KMS stub behind a `KeyService` interface; never hard-code keys.
8. Server responses are JSON; the frontend escapes all output. No server-built HTML.
9. No dynamic code compilation or `eval`-style rule execution. Business rules use Flowable DMN.
10. Every state change writes an audit event (actor, action, resource, outcome, time, request ID).
11. The legacy JDMS AES key is used only inside `jdms-migration`, read from a secret at runtime. Never print, log or commit it.

## Database portability rules (Oracle is added after the PoC)

- Data access through JPA repositories; any native SQL lives in a per-database adapter class.
- Flyway scripts go in `db/migration/postgresql/`; keep `db/migration/oracle/` present (empty is fine).
- Portable types only in core tables: UUID stored as `uuid` in PostgreSQL via a Hibernate type that maps to `RAW(16)` on Oracle; `timestamp with time zone`; no PostgreSQL arrays or enum types (store enums as `varchar`).
- Custom fields: one JSON column (`jsonb` on PostgreSQL), read and written only through the metadata repository.
- Identifiers 30 characters or fewer; no reserved words; lower_snake_case.
- Vectors are not stored in the database.

## Coding conventions

- Package by module, then by layer inside the module: `api` (public), `web` (controllers, DTOs), `domain`, `infra`.
- Controllers are thin; business rules live in domain services; domain objects never depend on web or infra.
- DTOs are Java records; no entity is ever serialized to the API.
- Errors use RFC 9457 `application/problem+json` with a stable `code` field.
- Every write endpoint accepts an `Idempotency-Key` header.
- Use `java.time`, never `Date`. Store UTC.
- Logging: structured (JSON), include `requestId`, `tenantId`, `userId`; never log document content, metadata values marked sensitive, tokens or keys.
- Frontend: function components and hooks, TanStack Query for server state, React Router, a single component library chosen by the UI/UX team (default in `docs/01-decisions.md`), all strings through i18n, logical CSS properties for RTL.

## Testing rules

- Unit tests for domain rules; integration tests with Testcontainers for anything touching DB, search, S3, Keycloak or Flowable. No H2, no mocks of the database.
- Authorization matrix tests for every endpoint (see `docs/02-poc-functional-spec.md` §6).
- Each backlog item lists done criteria; write tests that prove them before marking the item done.
- `mvn verify` and `npm test` must pass before you finish a task.

## How to work

1. Pick the first backlog item in `docs/05-backlog.md` whose status is `todo` and whose dependencies are `done`.
2. Read the referenced spec sections. If something is ambiguous, check `docs/01-decisions.md` defaults; if still unclear, choose the simplest option that meets the done criteria and record it in `docs/adr/NNNN-title.md`.
3. Make small, reviewable commits. Update the item's status in `docs/05-backlog.md` and note anything deferred.
4. Do not add new infrastructure components, frameworks or licences that are not in this file without an ADR. Allowed licences: Apache-2.0, MIT, BSD, EPL-2.0, LGPL (dynamic linking), MPL-2.0, OFL-1.1 (fonts). AGPL, SSPL, BUSL and commercial licences need explicit approval.
5. Do not build features outside the PoC scope in `docs/02-poc-functional-spec.md` §2.
