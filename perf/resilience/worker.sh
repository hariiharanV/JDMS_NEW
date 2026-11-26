#!/bin/bash
# Starts, kills (SIGKILL, as a crash) or reports the local jdms-worker (B-13 resilience rehearsal).
# Usage: perf/resilience/worker.sh start|kill|pid <log-file>; expects deploy/.env loaded (set -a; . deploy/.env).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKER_DIR="$ROOT/backend/jdms-worker"

# the worker JVM, by its class path (spring-boot:run) or jar; not by /proc/<pid>/cwd, which a shell with another
# group (sg docker) may not read
pids() {
  pgrep -f "$WORKER_DIR/target/classes|jdms-worker-[^ ]*\.jar" || true
}

case "${1:-}" in
  start)
    cd "$WORKER_DIR"
    nohup ../mvnw -q spring-boot:run -Dspring-boot.run.profiles=local > "${2:-/dev/null}" 2>&1 &
    ;;
  kill)
    for pid in $(pids); do kill -9 "$pid"; done
    ;;
  pid)
    pids
    ;;
  *)
    echo "usage: $0 start|kill|pid [log-file]" >&2
    exit 2
    ;;
esac
