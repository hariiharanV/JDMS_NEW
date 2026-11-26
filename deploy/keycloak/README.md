# Keycloak realm for local development

`jdms-local-realm.json` is imported by `deploy/docker-compose.yml` into a new Keycloak container
(`--import-realm`; an existing realm is not overwritten, so recreate the container after editing the file).

It contains:

- the nine PoC realm roles (MAKER, CHECKER, COMMITTEE_MEMBER, VIEWER, RECORDS_OFFICER, TENANT_ADMIN,
  SECURITY_ADMIN, INTEGRATION_CLIENT, MIGRATION);
- a declarative user profile in which `tenant`, `branch_scope`, `home_branch` and `clearance` can be viewed and
  edited by administrators only (ADR-0007);
- clients `jdms-web` (public, authorization code + PKCE S256) and `jdms-integration-test` (client credentials), both
  with mappers for the `tenant`, `branch_scope`, `home_branch`, `clearance` and `roles` claims and the `jdms-api`
  audience; the integration client also gets `client_id`; `jdms-migration` (client credentials) is the legacy
  migration tool (ADR-0020), with MIGRATION plus TENANT_ADMIN and SECURITY_ADMIN to propose the catalog;
- the dev users (two of each admin role, for maker-checker) and the two clients' service accounts (see the root `README.md`),
  each with a fixed ID, so recreating the container keeps their token subjects (JDMS profiles are keyed by subject).

No password or secret is stored in the file: `${JDMS_DEV_USER_PASSWORD}`,
`${JDMS_INTEGRATION_TEST_CLIENT_SECRET}` and `${JDMS_MIGRATION_CLIENT_SECRET}` are resolved by Keycloak from environment variables at import, supplied
from `deploy/.env`. The file is also used by `KeycloakIntegrationIT`, which runs it in a Keycloak container.

## Login theme

`themes/jdms/login/` is the JDMS Next sign-in page (UI handoff §6.11, ADR-0022): the `keycloak.v2` pages inside a
split brand/form frame (only `template.ftl` is overridden), English and Arabic messages, and IBM Plex fonts served
from the theme (OFL-1.1, `resources/fonts/OFL.txt`). `docker-compose.yml` mounts `themes/` into the container and the
realm import sets `"loginTheme": "jdms"`. `JDMS_ENV_LABEL` sets the tag beside the wordmark ("LOCAL DEVELOPMENT" by
default; empty for none). Keycloak in `start-dev` does not cache themes, so edits show on the next page load.
A Keycloak without the theme (such as the one `KeycloakIntegrationIT` starts) falls back to its built-in theme.
