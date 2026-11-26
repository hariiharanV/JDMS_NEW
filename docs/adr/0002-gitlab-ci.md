# 0002 — Self-hosted GitLab and GitLab CI

- Status: Accepted
- Date: 2026-10-24
- Decided by: product owner (closes open decision O-09)

## Context

O-09 defaulted to GitHub and GitHub Actions. JMR runs a self-hosted GitLab
(`git@192.168.1.181:Atul.Arvind/jdms_next.git`).

## Decision

- Source control and CI on the self-hosted GitLab. The pipeline is `.gitlab-ci.yml` at the repository root
  (replaces `.github/workflows/` in the `CLAUDE.md` layout).
- Backend jobs need a Docker-capable runner for Testcontainers (Docker socket or Docker-in-Docker service).
- Supply-chain checks run as CI jobs with tools that are themselves on the allowed-licence list:
  - SBOM: CycloneDX Maven plugin (Apache-2.0) and `@cyclonedx/cyclonedx-npm` (Apache-2.0)
  - Licence check: `license-maven-plugin` (MIT) for Maven and `license-checker-rseidelsohn` (BSD-3-Clause) for npm,
    both failing on licences outside the `CLAUDE.md` allow-list
  - Vulnerability scan: Trivy (Apache-2.0) over both SBOMs. GitLab's built-in dependency scanning needs
    GitLab Ultimate, so it is not assumed.

## Consequences

- CI tools are build-time only; they are not runtime components and add no licence obligations to the product.
- If the GitLab instance has no Docker-capable runner, the backend job fails at the Testcontainers tests;
  the runner requirement is documented in `README.md`.
