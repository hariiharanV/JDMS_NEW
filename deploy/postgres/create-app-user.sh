#!/bin/sh
# Runs once, when the postgres container initialises an empty data directory. Creates the login the applications
# connect with (JDMS_DB_USERNAME); the schema owner (POSTGRES_USER) runs the migrations, which grant this user data
# access but only INSERT and SELECT on audit tables (ADR-0018). Values are passed as psql variables, never
# interpolated into SQL text.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v app_user="$JDMS_DB_USERNAME" -v app_password="$JDMS_DB_PASSWORD" <<'SQL'
create role :"app_user" login password :'app_password';
SQL
