#!/bin/bash
# Backup rehearsal (B-13): PostgreSQL (logical dump, roles without passwords) and the object store (encrypted objects,
# copied as they are), with timings. Keys are not in the backup set: the tenant key's master (KMS) is backed up by the
# secret store; the manifest records only its fingerprint. The search index is not backed up (rebuilt from the
# database). Usage, from the repository root with deploy/.env loaded:  perf/backup/backup.sh <backup-dir>
set -euo pipefail
DIR=${1:?usage: backup.sh <backup-dir>}
POSTGRES=${JDMS_POSTGRES_CONTAINER:-jdms-postgres-1}
RCLONE_IMAGE=rclone/rclone:1.75.2
mkdir -p "$DIR"
TIMINGS="$DIR/timings.tsv"
: > "$TIMINGS"
step() { echo -e "$1\t$2" >> "$TIMINGS"; echo "$1: $2 s"; }
psql_owner() {
  docker exec -i -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" psql -U "$JDMS_DB_OWNER_USERNAME" \
    -d "${JDMS_DB_NAME:-jdms}" -At -v ON_ERROR_STOP=1 "$@"
}

started=$(date +%s.%N)
date -u +%Y-%m-%dT%H:%M:%SZ > "$DIR/started-at.txt"
# counts first: everything the dump holds was committed before it started
psql_owner > "$DIR/manifest-db.tsv" <<'SQL'
select 'documents', count(*) from document union all
select 'versions', count(*) from document_version union all
select 'renditions', count(*) from rendition union all
select 'audit_events', count(*) from audit_event union all
select 'audit_last_seq', coalesce(max(chain_seq), 0) from audit_event;
SQL

t=$(date +%s.%N)
docker exec -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" pg_dumpall -U "$JDMS_DB_OWNER_USERNAME" \
  --roles-only --no-role-passwords > "$DIR/roles.sql"
docker exec -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" pg_dump -U "$JDMS_DB_OWNER_USERNAME" \
  -d "${JDMS_DB_NAME:-jdms}" -Fc -Z 1 > "$DIR/jdms.dump"
step "postgres_dump" "$(echo "$(date +%s.%N) - $t" | bc)"

# objects after the dump: every object a dumped row refers to existed before the dump started (blobs are written
# before their row commits and are never changed), so the copy is consistent with the dump
t=$(date +%s.%N)
docker run --rm --network host --user "$(id -u):$(id -g)" -v "$DIR:/backup" \
  -e RCLONE_CONFIG_SRC_TYPE=s3 -e RCLONE_CONFIG_SRC_PROVIDER=SeaweedFS \
  -e RCLONE_CONFIG_SRC_ENDPOINT="http://localhost:${S3_PORT:-8333}" \
  -e RCLONE_CONFIG_SRC_ACCESS_KEY_ID="$JDMS_S3_ACCESS_KEY" -e RCLONE_CONFIG_SRC_SECRET_ACCESS_KEY="$JDMS_S3_SECRET_KEY" \
  "$RCLONE_IMAGE" sync "src:${JDMS_S3_BUCKET:-jdms-local}" /backup/objects --transfers 32 --checkers 32 \
  --fast-list --stats-one-line --stats 60s -q
step "objects_copy" "$(echo "$(date +%s.%N) - $t" | bc)"

find "$DIR/objects" -type f | wc -l | sed 's/^/objects\t/' > "$DIR/manifest-objects.tsv"
du -sb "$DIR/objects" | cut -f1 | sed 's/^/object_bytes\t/' >> "$DIR/manifest-objects.tsv"
du -sb "$DIR/jdms.dump" | cut -f1 | sed 's/^/dump_bytes\t/' >> "$DIR/manifest-objects.tsv"
# which master key the restore needs (a fingerprint only, never the key)
printf '%s' "$JDMS_LOCAL_KMS_MASTER_KEY" | sha256sum | cut -c1-16 | sed 's/^/kms_key_fingerprint\t/' >> "$DIR/manifest-objects.tsv"
step "total" "$(echo "$(date +%s.%N) - $started" | bc)"
