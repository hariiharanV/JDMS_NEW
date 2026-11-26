-- B-11: audit chain position, verification results, and the application user's privileges (ADR-0018).
-- Runs as the schema owner. ${app_user} is the login the application connects with (spring.datasource.username).

-- Position of each event in its tenant's chain (1, 2, ...), assigned under the chain-head lock. Not hashed: the
-- verification job walks events by position and checks each prev_hash link, so a reordered or removed row breaks it.
alter table audit_event add column chain_seq bigint;

-- Existing events: follow each tenant's chain from the genesis hash.
with recursive chain (id, tenant_id, hash, seq) as (
    select e.id, e.tenant_id, e.hash, cast(1 as bigint)
    from audit_event e
    where e.prev_hash = '0000000000000000000000000000000000000000000000000000000000000000'
    union all
    select e.id, e.tenant_id, e.hash, c.seq + 1
    from audit_event e
    join chain c on e.tenant_id = c.tenant_id and e.prev_hash = c.hash
)
update audit_event a set chain_seq = c.seq from chain c where a.id = c.id;

alter table audit_event alter column chain_seq set not null;
alter table audit_event add constraint uq_audit_event_seq unique (tenant_id, chain_seq);

-- One row per verification run and tenant (append-only, like the events it checks).
create table audit_verification (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    started_at      timestamp with time zone not null,
    finished_at     timestamp with time zone not null,
    trigger_type    varchar(10)              not null,
    status          varchar(10)              not null,
    events_checked  bigint                   not null,
    head_seq        bigint                   not null,
    head_hash       varchar(64)              not null,
    broken_seq      bigint,
    broken_event_id uuid,
    failure         varchar(30),
    constraint pk_audit_verification primary key (id),
    constraint fk_audit_verif_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_audit_verif_trigger check (trigger_type in ('SCHEDULE', 'REQUEST')),
    constraint ck_audit_verif_status check (status in ('VALID', 'BROKEN')),
    constraint ck_audit_verif_failure check (failure in ('HASH_MISMATCH', 'LINK_BROKEN', 'SEQUENCE_GAP',
        'HEAD_MISMATCH'))
);
create index ix_audit_verif_tenant_time on audit_verification (tenant_id, finished_at);

-- Privileges: the application user reads and writes data but cannot change the schema's tables, the migration
-- history, or audit rows (spec DM audit: INSERT and SELECT only). Tables created by later migrations get DML through
-- the default privileges; an append-only table must revoke UPDATE and DELETE explicitly, as below.
-- CREATE on the schema is for Flowable, which manages its own ACT_* tables (ADR-0013).
do $$
begin
    if '${app_user}' = current_user then
        raise warning 'The application connects as the schema owner (%); audit rows are not protected by grants. '
            'Configure a separate application user (ADR-0018).', current_user;
        return;
    end if;
    execute format('grant usage, create on schema %I to %I', '${flyway:defaultSchema}', '${app_user}');
    execute format('grant select, insert, update, delete on all tables in schema %I to %I',
        '${flyway:defaultSchema}', '${app_user}');
    execute format('alter default privileges in schema %I grant select, insert, update, delete on tables to %I',
        '${flyway:defaultSchema}', '${app_user}');
    execute format('revoke all on table %I from %I', '${flyway:table}', '${app_user}');
    execute format('revoke update, delete, truncate on table audit_event, audit_verification from %I',
        '${app_user}');
end
$$;
