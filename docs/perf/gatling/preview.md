# First-page preview under load (B-13)

Spec §7: first-page preview p95 below 2 s. 5 document opens per second for 180 s by perfmaker1 over the processed uploads of the ingest run (PDF previews and image originals); each open fetches the preview and the thumbnail. Same 2-vCPU machine as [../results.md](../results.md), 1 million documents in the tenant.

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 1800 | 0 | 57 | 67 | 117 | 173 | 278 | 64 |
| first-page preview | 900 | 0 | 57 | 79 | 122 | 166 | 278 | 67 |
| thumbnail | 900 | 0 | 56 | 63 | 102 | 177 | 276 | 60 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | OK |
| first-page preview: 95th percentile of response time is less than 2000.0 | OK |
