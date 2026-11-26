#!/bin/bash
# Restore rehearsal (B-13): empty PostgreSQL, object store and OpenSearch (project jdms-restore, data under
# JDMS_RESTORE_DIR), then the dump and the objects from a backup made by backup.sh, with timings. The master key comes
# from the environment (the secret store in production). Starting a core on the result and rebuilding the index are
# separate steps (docs/perf/README.md). Usage, from the repository root with deploy/.env loaded:
#   JDMS_RESTORE_DIR=/u01/restore perf/backup/restore.sh <backup-dir>
set -euo pipefail
DIR=${1:?usage: restore.sh <backup-dir>}
: "${JDMS_RESTORE_DIR:?set JDMS_RESTORE_DIR}"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RCLONE_IMAGE=rclone/rclone:1.75.2
COMPOSE=(docker compose -p jdms-restore -f "$ROOT/deploy/docker-compose.yml" -f "$ROOT/perf/backup/docker-compose.restore.yml")
TIMINGS="$DIR/restore-timings.tsv"
: > "$TIMINGS"
step() { echo -e "$1\t$2" >> "$TIMINGS"; echo "$1: $2 s"; }
started=$(date +%s.%N)

mkdir -p "$JDMS_RESTORE_DIR/postgres" "$JDMS_RESTORE_DIR/seaweedfs" "$JDMS_RESTORE_DIR/opensearch"
# OpenSearch runs as uid 1000 in its image
chmod 777 "$JDMS_RESTORE_DIR/opensearch"
t=$(date +%s.%N)
"${COMPOSE[@]}" up -d --wait postgres seaweedfs opensearch
step "services_start" "$(echo "$(date +%s.%N) - $t" | bc)"

POSTGRES=jdms-restore-postgres-1
t=$(date +%s.%N)
# roles first (the application user already exists: created with its password from the secret store on first start)
docker exec -i -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" psql -U "$JDMS_DB_OWNER_USERNAME" -d postgres \
  -q < "$DIR/roles.sql" > /dev/null 2>&1 || true
docker exec -i -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" pg_restore -U "$JDMS_DB_OWNER_USERNAME" \
  -d "${JDMS_DB_NAME:-jdms}" --exit-on-error --jobs 1 < "$DIR/jdms.dump"
step "postgres_restore" "$(echo "$(date +%s.%N) - $t" | bc)"

t=$(date +%s.%N)
docker run --rm --network host --user "$(id -u):$(id -g)" -v "$DIR:/backup:ro" \
  -e RCLONE_CONFIG_DST_TYPE=s3 -e RCLONE_CONFIG_DST_PROVIDER=SeaweedFS \
  -e RCLONE_CONFIG_DST_ENDPOINT="http://localhost:${JDMS_RESTORE_S3_PORT:-18333}" \
  -e RCLONE_CONFIG_DST_ACCESS_KEY_ID="$JDMS_S3_ACCESS_KEY" -e RCLONE_CONFIG_DST_SECRET_ACCESS_KEY="$JDMS_S3_SECRET_KEY" \
  "$RCLONE_IMAGE" copy /backup/objects "dst:${JDMS_S3_BUCKET:-jdms-local}" --transfers 32 --checkers 32 -q
step "objects_restore" "$(echo "$(date +%s.%N) - $t" | bc)"
step "total" "$(echo "$(date +%s.%N) - $started" | bc)"
