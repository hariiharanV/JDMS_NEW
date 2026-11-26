# Backend licence check

`mvn verify` runs `license-maven-plugin` (`add-third-party`) on every module and fails when a shipped
(non-test) dependency has no licence on the `CLAUDE.md` allow-list. A dependency with several licences passes
when at least one of them is allowed.

| File | Purpose |
| --- | --- |
| `licence-allow-list.txt` | Allowed licences as SPDX IDs (Apache-2.0, MIT, BSD, EPL-2.0, LGPL, MPL-2.0) |
| `licence-merges.txt` | Maps licence names as published in POMs to SPDX IDs: `SPDX-ID\|published name\|…` |

Notes on the mapping:

- Eclipse Distribution License 1.0 (EDL) is the BSD-3-Clause text under another name (SPDX lists it as
  BSD-3-Clause), so it is mapped to `BSD-3-Clause`.
- LGPL is allowed only with dynamic linking (CLAUDE.md); Maven JAR dependencies are linked dynamically.
- MIT-0 (MIT No Attribution) is the MIT licence without the attribution clause, i.e. more permissive than MIT; it is
  allowed as part of the MIT family (first seen: `org.reactivestreams:reactive-streams`, required by the AWS SDK).

When a new dependency fails the check, run
`mvn -DskipTests -Dlicence.enforce=false verify` and read `*/target/generated-sources/license/THIRD-PARTY.txt`.
If the licence is allowed under a new name, add the name to `licence-merges.txt`. If it is not allowed (AGPL,
SSPL, BUSL, GPL without classpath exception, commercial), do not add the dependency without approval and an ADR.

A dependency whose POM declares no licence fails the build (`failOnMissing`). If that happens, confirm its licence
by hand and add the plugin's `missingFile`/`useMissingFile` override, noting the source of the licence.
