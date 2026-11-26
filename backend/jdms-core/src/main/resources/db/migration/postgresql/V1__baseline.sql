-- Baseline for the JDMS Next schema. Tables are added by later migrations, one backlog item at a time.
-- Portability rules (CLAUDE.md): identifiers <= 30 characters, lower_snake_case, no reserved words,
-- no PostgreSQL arrays or enum types, uuid / timestamp with time zone / jsonb only where the rules allow.
-- Every PostgreSQL migration needs an equivalent in db/migration/oracle once Oracle support is added.
SELECT 1;
