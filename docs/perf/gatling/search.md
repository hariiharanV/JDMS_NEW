# Search load, 1,000,062 documents (B-13)

Environment: one 2-vCPU, 22 GB machine running the whole stack and Gatling; OpenSearch heap 2 GB (perf/docker-compose.perf.yml). Open model: constant arrival rate after a 30 s ramp. Users: perfviewer1 (all branches, CONFIDENTIAL) and perfbranch1 (3 branches, INTERNAL).

## 10 searches/s for 300 s (saturated)

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 3165 | 0 | 5168 | 8950 | 14382 | 20160 | 31442 | 5660 |
| search: by CIF | 780 | 0 | 4490 | 8360 | 14391 | 19813 | 31442 | 5220 |
| search: words, 3 branches + INTERNAL | 325 | 0 | 5491 | 8912 | 13666 | 18948 | 27483 | 5562 |
| search: words (ar) | 459 | 0 | 5255 | 9203 | 14834 | 20368 | 30922 | 5812 |
| search: words (en) | 1274 | 0 | 5563 | 9204 | 14451 | 20672 | 26674 | 5994 |
| search: filters and date range | 327 | 0 | 4290 | 8571 | 14187 | 20498 | 23242 | 5290 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | OK |
| Global: 95th percentile of response time is less than 1500.0 | KO |
| search: words (en): 95th percentile of response time is less than 1500.0 | KO |
| search: by CIF: 95th percentile of response time is less than 1500.0 | KO |

## 7 searches/s for 180 s

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 1380 | 0 | 296 | 470 | 835 | 1278 | 1624 | 350 |
| search: words, 3 branches + INTERNAL | 155 | 0 | 269 | 354 | 535 | 1012 | 1085 | 302 |
| search: by CIF | 364 | 0 | 99 | 135 | 275 | 503 | 605 | 120 |
| search: words (en) | 534 | 0 | 477 | 641 | 1068 | 1429 | 1624 | 535 |
| search: words (ar) | 200 | 0 | 335 | 450 | 713 | 1060 | 1274 | 377 |
| search: filters and date range | 127 | 0 | 224 | 299 | 530 | 734 | 845 | 246 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | OK |
| Global: 95th percentile of response time is less than 1500.0 | OK |
| search: words (en): 95th percentile of response time is less than 1500.0 | OK |
| search: by CIF: 95th percentile of response time is less than 1500.0 | OK |

## 5 searches/s for 60 s

| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| All Requests | 390 | 0 | 307 | 445 | 705 | 943 | 1006 | 326 |
| search: words (ar) | 54 | 0 | 307 | 381 | 485 | 665 | 667 | 320 |
| search: words (en) | 163 | 0 | 454 | 544 | 832 | 996 | 1006 | 474 |
| search: by CIF | 93 | 0 | 93 | 125 | 252 | 401 | 418 | 112 |
| search: words, 3 branches + INTERNAL | 44 | 0 | 284 | 346 | 445 | 626 | 626 | 298 |
| search: filters and date range | 36 | 0 | 224 | 268 | 506 | 719 | 719 | 251 |

| Assertion | Status |
| --- | --- |
| Global: percentage of failed events is less than 1.0 | OK |
| Global: 95th percentile of response time is less than 1500.0 | OK |
| search: words (en): 95th percentile of response time is less than 1500.0 | OK |
| search: by CIF: 95th percentile of response time is less than 1500.0 | OK |
