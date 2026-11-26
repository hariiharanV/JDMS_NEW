# 0003 — SeaweedFS as the S3 store for local development and tests

- Status: Accepted
- Date: 2026-10-24
- Decided by: product owner

## Context

Open decision O-01 (production object store) defaults to "any S3-compatible container" for development.
MinIO was the first choice, but the open-source MinIO repository was archived in April 2026 and its
community images are no longer published on Docker Hub or Quay (pulls fail). Using it would mean
building a frozen, unpatched AGPL-3.0 image ourselves.

## Decision

Use SeaweedFS (Apache-2.0, maintained, official `chrislusf/seaweedfs` images) with its S3 gateway for
`deploy/docker-compose.yml` and for Testcontainers-based S3 tests. The core talks to storage only through the
`BlobStore` interface over the S3 API (AWS SDK v2), so the store is configuration, not code.

## Consequences

- O-01 (production store) stays open; the B-10 object-lock tests run against the production candidate and
  are tagged `requires-worm-store` until then. SeaweedFS object-lock support is not relied on.
- No AGPL component is needed in the development environment.
