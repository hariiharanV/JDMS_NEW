-- B-01 (start of B-11): append-only audit trail with a per-tenant hash chain (docs/03-domain-model.md, audit).
-- The INSERT/SELECT-only grant for the application user and the verification job are added in B-11.

create table audit_event (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    occurred_at     timestamp with time zone not null,
    actor_type      varchar(10)              not null,
    actor_id        varchar(64)              not null,
    action          varchar(80)              not null,
    resource_type   varchar(50)              not null,
    resource_id     varchar(100),
    outcome         varchar(10)              not null,
    client_ip       varchar(45),
    request_id      varchar(64),
    details         jsonb                    not null,
    prev_hash       varchar(64)              not null,
    hash            varchar(64)              not null,
    constraint pk_audit_event primary key (id),
    constraint fk_audit_event_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_audit_event_actor_type check (actor_type in ('USER', 'CLIENT', 'SYSTEM')),
    constraint ck_audit_event_outcome check (outcome in ('SUCCESS', 'DENIED', 'ERROR'))
);
create index ix_audit_event_tenant_time on audit_event (tenant_id, occurred_at);
create index ix_audit_event_resource on audit_event (tenant_id, resource_type, resource_id);
create index ix_audit_event_actor on audit_event (tenant_id, actor_id, occurred_at);

-- One row per tenant: the latest event of its chain. Locked FOR UPDATE while appending.
create table audit_chain_head (
    tenant_id       uuid                     not null,
    last_event_id   uuid,
    last_hash       varchar(64)              not null,
    event_count     bigint                   not null,
    constraint pk_audit_chain_head primary key (tenant_id),
    constraint fk_audit_chain_head_tenant foreign key (tenant_id) references tenant (id)
);
