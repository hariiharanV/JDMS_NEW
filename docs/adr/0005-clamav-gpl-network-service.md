# 0005 — ClamAV (GPL-2.0) is used only as a separate network service

- Status: Accepted
- Date: 2026-10-24

## Context

ClamAV is decided (D-11) but is licensed GPL-2.0, which is not on the `CLAUDE.md` licence allow-list.

## Decision

ClamAV runs as its own container (`clamd`). `jdms-worker` talks to it over the clamd TCP protocol with our own
client code; no ClamAV code or library is linked into or shipped inside JDMS Next artefacts. The Maven and npm
licence checks cover linked dependencies only; ClamAV is listed in the deployment licence inventory as a
separately distributed third-party service.

## Consequences

- No ClamAV Java binding that wraps GPL code may be added as a dependency.
- The same rule applies to any other non-allow-listed engine that is used only over a network protocol.
