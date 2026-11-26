# Permission revocation (B-13)

Spec §7: effective within 60 s on every path. A security admin (perfsec1) proposes a DENY rule on one document for the VIEWER role, a second one (perfsec2) approves it, and perfviewer1 opens and searches for the document until both refuse it (detail 404, absent from search results). Single core: the rule cache is evicted on the approving node when the change commits; other nodes see the change within the cache TTL (30 s, proven by `AccessRuleAdminIT.ruleChangesNotSignalledToThisNodeApplyWithinTheCacheTtl`).

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 15 | 0 | 139 | 413 | 940 | 1031 | 1031 | 266 |
| pick a visible document | 3 | 0 | 499 | 626 | 668 | 668 | 668 | 471 |
| propose DENY rule | 3 | 0 | 82 | 372 | 469 | 469 | 469 | 207 |
| approve DENY rule | 3 | 0 | 193 | 822 | 1031 | 1031 | 1031 | 454 |
| viewer opens the document | 3 | 0 | 31 | 90 | 110 | 110 | 110 | 56 |
| viewer searches for it | 3 | 0 | 139 | 148 | 151 | 151 | 151 | 140 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is 0.0 | OK |

| Wall-clock latency | Count | Missed | p50 ms | p95 ms | Max ms | Target p95 ms | Met |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| revocation effective (detail and search) | 3 | 0 | 192 | 333 | 333 | 60000 | yes |

In all three rounds the first check after the approval already found both paths refusing.
