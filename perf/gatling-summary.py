"""Markdown summary of a Gatling HTML report (B-13): one row per request with counts and response-time percentiles,
plus the assertions and the simulation's wall-clock latencies (target/gatling/<simulation>-latencies.json) if any.

Usage: python3 perf/gatling-summary.py <report-dir> [<latencies.json>]
"""
import html
import json
import re
import sys
from pathlib import Path

ROW = re.compile(r'class="ellipsed-name">([^<]+)</span>.*?((?:<td class="value[^"]*">[^<]*</td>\s*){13})', re.S)
CELL = re.compile(r'<td class="value[^"]*">([^<]*)</td>')
ASSERTION = re.compile(r'<td class="error-col-1[^"]*">\s*([^<]+?)\s*<span.*?</td>\s*<td class="error-col-2[^"]*">'
                       r'\s*([^<]+?)\s*</td>', re.S)


def main():
    report = Path(sys.argv[1])
    page = (report / "index.html").read_text(encoding="utf-8")
    out = ["| Request | Count | KO | p50 ms | p75 ms | p95 ms | p99 ms | Max ms | Mean ms |",
           "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    seen = set()
    for name, cells in ROW.findall(page):
        name = html.unescape(name.strip())
        if name in seen:
            continue
        seen.add(name)
        total, ok, ko, _, _, _, p50, p75, p95, p99, high, mean, _ = CELL.findall(cells)
        out.append(f"| {name} | {total} | {ko} | {p50} | {p75} | {p95} | {p99} | {high} | {mean} |")
    assertions = ASSERTION.findall(page)
    if assertions:
        out += ["", "| Assertion | Status |", "| --- | --- |"]
        out += [f"| {html.unescape(a)} | {s} |" for a, s in assertions]
    if len(sys.argv) > 2 and Path(sys.argv[2]).exists():
        latencies = json.loads(Path(sys.argv[2]).read_text())
        out += ["", "| Wall-clock latency | Count | Missed | p50 ms | p95 ms | Max ms | Target p95 ms | Met |",
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
        for metric, v in latencies.items():
            out.append(f"| {metric} | {v.get('count')} | {v.get('missed')} | {v.get('p50_ms', '')} | "
                       f"{v.get('p95_ms', '')} | {v.get('max_ms', '')} | {v.get('target_p95_ms', '')} | "
                       f"{'yes' if v.get('met') else 'no' if 'met' in v else ''} |")
    print("\n".join(out))


if __name__ == "__main__":
    main()
