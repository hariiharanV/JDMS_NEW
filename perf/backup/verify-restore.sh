#!/bin/bash
# Checks a restore made by restore.sh (B-13): row counts against the backup's manifest, then content: a random sample
# of versions decrypted with the master key from the environment and compared with their SHA-256 (all must match),
# and the same with a random key (none may decrypt). Usage, from the repository root with deploy/.env loaded:
#   perf/backup/verify-restore.sh <backup-dir> [sample]
set -euo pipefail
DIR=${1:?usage: verify-restore.sh <backup-dir> [sample]}
SAMPLE=${2:-1000}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
POSTGRES=jdms-restore-postgres-1
JAR=$(ls "$ROOT"/backend/jdms-perf/target/jdms-perf-*-exec.jar)

echo "== row counts (backup manifest | restored)"
docker exec -i -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" psql -U "$JDMS_DB_OWNER_USERNAME" \
  -d "${JDMS_DB_NAME:-jdms}" -At -F $'\t' <<'SQL' > "$DIR/restored-db.tsv"
select 'documents', count(*) from document union all
select 'versions', count(*) from document_version union all
select 'renditions', count(*) from rendition union all
select 'audit_events', count(*) from audit_event union all
select 'audit_last_seq', coalesce(max(chain_seq), 0) from audit_event;
SQL
# by name (the manifest's file uses "|" between columns, this one a tab)
join -t $'\t' <(tr '|' '\t' < "$DIR/manifest-db.tsv" | sort) <(sort "$DIR/restored-db.tsv") \
  | awk -F'\t' '{print $1, $2, $3, ($2 == $3) ? "same" : "DIFFERENT"}'

perf() {
  java -jar "$JAR" verify-content --sample="$SAMPLE" --jdms.perf.tenant-code=PERF \
    --spring.datasource.url="jdbc:postgresql://localhost:${JDMS_RESTORE_DB_PORT:-15432}/${JDMS_DB_NAME:-jdms}" \
    --jdms.storage.endpoint="http://localhost:${JDMS_RESTORE_S3_PORT:-18333}" "$@" 2>&1 \
    | grep -o '"message":"Content check[^"]*"' || true
}
echo "== content with the master key from the secret store (every sampled version must match)"
perf
echo "== content with a random key (nothing may decrypt)"
JDMS_LOCAL_KMS_MASTER_KEY=$(head -c 32 /dev/urandom | base64) perf
