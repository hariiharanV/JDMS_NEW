# Ingest under load, 1,000,062 documents in the tenant (B-13)

Environment: one 2-vCPU, 22 GB machine running the whole stack, one worker (4 concurrent jobs) and Gatling. Uploads by perfmaker1 into ACCOUNT_OPENING_FORM (single approval): digital PDFs of 1 to 4 pages, and 20% scanned PNG pages that need OCR. Each upload carries a unique CIF (metadata check) and a unique 12-letter word in its content only (content check). Latencies are wall-clock from the upload's response.

## 0.5 uploads/s for 600 s (300 uploads)

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 3421 | 0 | 89 | 200 | 1122 | 3083 | 6968 | 260 |
| upload | 300 | 0 | 263 | 513 | 2561 | 5592 | 6893 | 630 |
| metadata search (by CIF) | 983 | 0 | 86 | 155 | 605 | 1756 | 3147 | 172 |
| content search (digital) | 605 | 0 | 98 | 199 | 1123 | 2590 | 6585 | 264 |
| content search (scan) | 854 | 0 | 88 | 230 | 1441 | 4645 | 6968 | 344 |
| versions | 379 | 0 | 41 | 72 | 294 | 1064 | 2413 | 88 |
| first-page preview | 300 | 0 | 76 | 124 | 583 | 1248 | 4575 | 153 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | OK |
| upload: percentage of failed events is 0.0 | OK |
| first-page preview: 95th percentile of response time is less than 2000.0 | OK |

| Wall-clock latency | Count | Missed | p50 ms | p95 ms | Max ms | Target p95 ms | Met |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| content searchable (digital) | 257 | 0 | 3955 | 7072 | 11313 | 120000 | yes |
| content searchable (scan) | 41 | 2 | 12434 | 17578 | 21306 | 600000 | no |
| metadata searchable after upload | 300 | 0 | 956 | 2304 | 4155 | 2000 | no |


## 3 uploads/s for 120 s (360 uploads): overload

Far above what one worker on this machine processes. Every waiting upload also polled every 2 s (versions and searches), so the run's own polling (32,000 requests in 12 minutes) added to the overload; the throughput run below uploads without polling. Kept to show behaviour under overload: uploads slow down and some time out at 60 s, nothing is lost, the queue drains.

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 32421 | 325 | 378 | 1603 | 23432 | 35082 | 60096 | 3797 |
| upload | 360 | 26 | 18555 | 30021 | 39063 | 50276 | 54808 | 19715 |
| metadata search (by CIF) | 473 | 40 | 18200 | 28857 | 40885 | 52655 | 60001 | 19183 |
| content search (scan) | 5065 | 54 | 887 | 3014 | 25014 | 37387 | 55508 | 4706 |
| content search (digital) | 3817 | 150 | 12252 | 21747 | 34125 | 47336 | 60096 | 14696 |
| versions | 22390 | 54 | 226 | 537 | 5682 | 23726 | 40443 | 1187 |
| first-page preview | 316 | 1 | 253 | 659 | 9008 | 22955 | 26818 | 1311 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | KO |
| upload: percentage of failed events is 0.0 | KO |
| first-page preview: 95th percentile of response time is less than 2000.0 | KO |

| Wall-clock latency | Count | Missed | p50 ms | p95 ms | Max ms | Target p95 ms | Met |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| content searchable (digital) | 21 | 243 | 148837 | 227069 | 242890 | 120000 | no |
| content searchable (scan) | 64 | 6 | 532531 | 569814 | 583862 | 600000 | no |
| metadata searchable after upload | 289 | 45 | 25421 | 51313 | 69158 | 2000 | no |
334|2026-10-25 17:12:43.665543+00|2026-10-25 17:15:21.679355+00|2026-10-25 17:24:19.623312+00|315|1629

Pipeline: 334 uploads were stored (the 26 failed ones timed out at the client before a response; none was stored twice), between 17:12:43 and 17:15:21 UTC; 315 were processed by 17:24:19 and 19 were still being processed when the counts were taken: about 1,600 per hour under this load.

## Throughput: 1 upload/s for 300 s, upload only (`-Dfollow=false`)

300 uploads (80% digital, 20% scans), no polling; the drain rate is read from the database
(`perf/ingest-throughput.sh`). Upload p50 686 ms, p95 6.0 s (the core shares the 2 vCPUs with processing).

| Versions | Done | Failed | First upload | Last upload | Last processed | Processed per hour |
| ---: | ---: | ---: | --- | --- | --- | ---: |
| 300 | 300 | 0 | 17:30:47 | 17:35:45 | 17:37:19 | 2,756 |

One worker (4 concurrent jobs) with ClamAV, Tika (Tesseract) and Gotenberg on the same 2 vCPUs as everything else:
about 2.6 CPU-seconds per document for the whole stack. See the scaling estimate in [../results.md](../results.md).
