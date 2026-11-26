# JDMS Next

Bank-grade document management system replacing the legacy JMR DMS (JDMS). Phase 0: proof of concept.
Instructions for contributors and Claude Code are in [`CLAUDE.md`](CLAUDE.md); the build pack is in [`docs/`](docs).

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25 | Java 25 is the target (ADR-0001). Set `JAVA_HOME` to a JDK 25 |
| Docker Engine + Compose | 29+ / v2+ | Local dependencies and Testcontainers. Your user must be in the `docker` group |
| Node.js | 24 (`frontend/.nvmrc`) | Frontend |

Maven is not needed: use the wrapper `backend/mvnw` (Maven 3.9.12).

## Local setup

```bash
# 1. Secrets for local services (git-ignored); replace every CHANGE_ME
cp deploy/.env.example deploy/.env

# 2. Start PostgreSQL, Keycloak, OpenSearch, SeaweedFS (S3), ClamAV, Tika and Gotenberg; waits until all are healthy.
#    The first start takes a few minutes while ClamAV downloads its signature database.
docker compose -f deploy/docker-compose.yml up -d --wait

# 3. Build and test the backend (unit + Testcontainers integration tests, Modulith verify, SBOM, licence check)
cd backend && ./mvnw verify

# 4. Run the core against the local services
set -a; . ../deploy/.env; set +a
cd jdms-core && ../mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 4b. In another terminal: the worker (antivirus, text, OCR, renditions), once the core has started
set -a; . ../deploy/.env; set +a
cd jdms-worker && ../mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 5. Frontend (sign in with the dev users of deploy/keycloak)
cd frontend && npm ci && npm run dev

# 6. UI journeys against the running stack (core and worker started, deploy/.env loaded)
cd frontend && npm run e2e:stack
```

### Legacy JDMS migration (B-12, ADR-0020)

`jdms-migration` reads a legacy JDMS database and file tree and imports through the core as the `jdms-migration`
client. Settings: `backend/jdms-migration/src/main/resources/migration.yml` and these environment variables:
`JDMS_LEGACY_DB_URL`, `JDMS_LEGACY_DB_USERNAME`, `JDMS_LEGACY_DB_PASSWORD` (read-only; add the bank's Oracle driver
to the classpath), `JDMS_LEGACY_FILES_ROOT`, `JDMS_LEGACY_SECRET_FILE` (a file holding the legacy secret, never the
value itself), `JDMS_CORE_URL`, `JDMS_OIDC_TOKEN_URI`, `JDMS_MIGRATION_CLIENT_ID`, `JDMS_MIGRATION_CLIENT_SECRET`,
`JDMS_MIGRATION_REPORT_DIR`. Legacy-to-JDMS mappings (roles, permission letters, workflow actions, types) go in a
YAML file passed with `--spring.config.additional-location=file:mapping.yml`.

```bash
cd backend && ./mvnw -pl jdms-migration -am package -DskipTests
java -jar jdms-migration/target/jdms-migration-0.1.0-SNAPSHOT-exec.jar catalog   # propose types, folders, folder permissions
#   ... administrators approve the proposals in the UI (Administration > Pending changes); repeat for nested folders
java -jar jdms-migration/target/jdms-migration-0.1.0-SNAPSHOT-exec.jar run       # full run, repeatable
java -jar jdms-migration/target/jdms-migration-0.1.0-SNAPSHOT-exec.jar run --jdms.migration.since=2026-10-01T00:00:00Z
```

Each run writes `reconciliation.json` and `reconciliation.md` under the report directory. Exit code 0: no exceptions;
3: exceptions listed in the report; 1: the run failed. The BANK1 tenant needs a rule allowing `IMPORT` and
`VIEW_METADATA` for role `MIGRATION` (new tenants get it in their baseline).

The core and the worker connect to PostgreSQL as the application user (`JDMS_DB_USERNAME`), which cannot change or
delete audit rows; migrations run as the schema owner (`JDMS_DB_OWNER_USERNAME`) (ADR-0018). A new database volume
gets the application user automatically. A volume created before B-11 needs it once:

```bash
set -a; . deploy/.env; set +a
docker exec -i -e JDMS_DB_USERNAME -e JDMS_DB_PASSWORD -e POSTGRES_USER="$JDMS_DB_OWNER_USERNAME" \
    -e POSTGRES_DB="$JDMS_DB_NAME" jdms-postgres-1 sh -s < deploy/postgres/create-app-user.sh
```

Stop the services with `docker compose -f deploy/docker-compose.yml down` (add `-v` to delete their data).

### Local users and tokens

The `local` profile seeds tenants `BANK1` and `BANK2` with branches. The Keycloak realm `jdms-local` has these
users (two of each admin role, so maker-checker can be exercised), all with the password `JDMS_DEV_USER_PASSWORD` from `deploy/.env`:

| User | Tenant | Roles | Branch scope | Clearance |
| --- | --- | --- | --- | --- |
| `maker1` | BANK1 | MAKER | 001 | INTERNAL |
| `checker1` | BANK1 | CHECKER | 001, 002 | CONFIDENTIAL |
| `viewer1` | BANK1 | VIEWER | 002 | INTERNAL |
| `records1` | BANK1 | RECORDS_OFFICER | ALL | RESTRICTED |
| `tenantadmin1` | BANK1 | TENANT_ADMIN | ALL | CONFIDENTIAL |
| `tenantadmin2` | BANK1 | TENANT_ADMIN | ALL | CONFIDENTIAL |
| `secadmin1` | BANK1 | SECURITY_ADMIN | ALL | RESTRICTED |
| `secadmin2` | BANK1 | SECURITY_ADMIN | ALL | RESTRICTED |
| `committee1`, `committee2`, `committee3` | BANK1 | COMMITTEE_MEMBER | ALL | CONFIDENTIAL |
| `bank2user1` | BANK2 | MAKER, VIEWER | ALL | CONFIDENTIAL |

Uploads of types with an approval process (`LOAN_AGREEMENT`: multi-stage, a credit-committee stage above 100,000;
`COLLATERAL_DEED`: single approval) wait in the approvers' inbox at `/api/v1/tasks` (ADR-0013). Committee approval
needs a committee with code `CREDIT_COMMITTEE`, created through `/api/v1/admin/committees` (members must have logged
in once).

Every tenant starts with baseline access rules (the role matrix in ADR-0012); security admins change them through
`/api/v1/admin/access-rules` with a second admin's approval. Users log in through the `jdms-web` client
(authorization code + PKCE). For API calls from the command line, use the
integration client:

```bash
set -a; . deploy/.env; set +a
TOKEN=$(curl -s -d grant_type=client_credentials -d client_id=jdms-integration-test \
  -d "client_secret=$JDMS_INTEGRATION_TEST_CLIENT_SECRET" \
  http://localhost:8180/realms/jdms-local/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me
```

Upload and download a document with that token:

```bash
curl -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -F 'metadata={"documentTypeCode":"PASSPORT","title":"Passport","branchCode":"001","cif":"CIF0001","expiryDate":"2030-01-01","customFields":{"passport_no":"A1234567","nationality":"OM","issue_date":"2020-01-01"}};type=application/json' \
  -F 'file=@passport.pdf;type=application/pdf' http://localhost:8080/api/v1/documents
```

After upload the worker scans, extracts text (OCR in English and Arabic for scans), and renders a PDF preview and a
thumbnail (ADR-0014): see `processingStatus` under `/versions`, then `/versions/{versionId}/preview` and
`/thumbnail`. Infected files are quarantined and the uploader finds a notification at `/api/v1/me/notifications`;
tenant admins see failed processing jobs at `/api/v1/admin/jobs?status=DEAD`.

Documents are searchable at `POST /api/v1/search` right after upload (metadata) and after processing (content), only
within what each user may view (ADR-0015). To rebuild a tenant's index from the database (for example after
deleting it or changing the mapping), a tenant admin calls `POST /api/v1/admin/search/rebuild` and follows the job
under `/api/v1/admin/jobs`; searches keep working on the old index until the new one is switched in.

Stored files are encrypted with keys derived from `JDMS_LOCAL_KMS_MASTER_KEY` in `deploy/.env`; if it is lost or
changed, documents stored with it cannot be read.

The realm file is re-imported only into a new Keycloak container; after editing it, recreate the container with
`docker compose -f deploy/docker-compose.yml up -d --force-recreate keycloak`.

## Local ports

All ports are bound to `127.0.0.1`. Service ports can be changed in `deploy/.env`.

| Port | What |
| --- | --- |
| 8080 | jdms-core API |
| 8081 | jdms-core management: `/actuator/health`, `/actuator/prometheus` |
| 8091 | jdms-worker management |
| 5180 | Frontend dev server (`npm run dev`), proxies `/api` to 8080 |
| 5432 | PostgreSQL |
| 8180 | Keycloak (realm `jdms-local`, issuer `http://localhost:8180/realms/jdms-local`; admin console with the bootstrap admin from `deploy/.env`) |
| 9200 | OpenSearch (no security plugin locally) |
| 8333 | SeaweedFS S3 gateway (credentials from `deploy/.env`) |
| 3310 | ClamAV (clamd) |
| 9998 | Apache Tika server |
| 3300 | Gotenberg |

## Repository layout

| Path | What |
| --- | --- |
| `backend/` | Maven multi-module build: `jdms-core`, `jdms-worker`, `jdms-migration`, `jdms-contracts`, `jdms-platform` (shared storage and job queue) |
| `backend/build/` | Licence allow-list and licence-name mappings for the Maven licence check |
| `frontend/` | React + TypeScript (Vite), Ant Design, English/Arabic with RTL |
| `deploy/` | `docker-compose.yml` for local dependencies, Keycloak realm, Tika image with Arabic OCR (`deploy/tika`) |
| `docs/` | Build pack: decisions, spec, domain model, API, backlog, legacy reference; `docs/adr/` for decisions |
| `.gitlab-ci.yml` | CI pipeline |

## Logging and monitoring

Applications log structured JSON (Logstash format) to stdout. Every API request gets an `X-Request-Id`
(a safe caller-supplied value is reused, otherwise a UUID is generated) that is returned in the response and
logged as `requestId`. Health and Prometheus metrics are served on the management port only.

## CI

The pipeline runs on the self-hosted GitLab (ADR-0002):

| Job | What |
| --- | --- |
| `backend:verify` | `./mvnw verify`: build, unit and Testcontainers integration tests, Modulith verify, ArchUnit, CycloneDX SBOM, licence check |
| `frontend:check` | lint, typecheck, unit tests, build, production licence check, CycloneDX SBOM |
| `frontend:e2e` | Playwright tests against the production build; report kept as an artefact |
| `security:vulnerability-scan` | Trivy over both SBOMs; fails on HIGH/CRITICAL vulnerabilities with a fix available |

`backend:verify` uses a Docker-in-Docker service for Testcontainers, so its runner must allow privileged
containers (or mount the host Docker socket; see the comment at the top of `.gitlab-ci.yml`).

## Licences

Allowed licences for shipped dependencies: Apache-2.0, MIT, BSD, EPL-2.0, LGPL (dynamic linking), MPL-2.0. AGPL,
SSPL, BUSL and commercial licences need explicit approval. Both the Maven and npm builds fail on anything else;
see `backend/build/README.md`. ClamAV (GPL-2.0) runs only as a separate service (ADR-0005).
