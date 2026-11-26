#!/bin/bash
# Pipeline drain rate after an upload-only ingest run (B-13): waits until every version uploaded to tenant PERF since
# <start> is processed, then prints count, first upload, last processed and documents per hour.
# Usage (deploy/.env loaded): perf/ingest-throughput.sh <start-instant, e.g. 2026-10-25T17:30:00Z>
set -euo pipefail
START=${1:?usage: ingest-throughput.sh <start-instant>}
POSTGRES=${JDMS_POSTGRES_CONTAINER:-jdms-postgres-1}
query() {
  docker exec -i -e PGPASSWORD="$JDMS_DB_OWNER_PASSWORD" "$POSTGRES" psql -U "$JDMS_DB_OWNER_USERNAME" \
    -d "${JDMS_DB_NAME:-jdms}" -At -F ' | ' -v start="$START" -v ON_ERROR_STOP=1
}
until [ "$(echo "select count(*) from document_version v join tenant t on t.id = v.tenant_id
                  where t.code = 'PERF' and v.created_at >= :'start'::timestamptz
                    and v.processing_status in ('PENDING', 'PROCESSING');" | query)" = "0" ]; do
  sleep 10
done
echo "versions | done | failed | first upload | last upload | last processed | processed per hour"
query <<'SQL'
select count(*), count(*) filter (where v.processing_status = 'DONE'),
       count(*) filter (where v.processing_status in ('FAILED', 'QUARANTINED')),
       min(v.created_at), max(v.created_at), max(v.updated_at),
       round(count(*) * 3600 / extract(epoch from (max(v.updated_at) - min(v.created_at))))
  from document_version v join tenant t on t.id = v.tenant_id
 where t.code = 'PERF' and v.created_at >= :'start'::timestamptz;
SQL
