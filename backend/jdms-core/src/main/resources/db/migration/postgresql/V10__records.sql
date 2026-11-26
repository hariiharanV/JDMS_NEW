-- B-10: retention, legal holds, disposition, tombstones, record events (docs/03-domain-model.md, module records).

create table retention_policy (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    code                varchar(30)              not null,
    name                varchar(200)             not null,
    -- CREATION_DATE, EXPIRY_DATE or EVENT (event_type, e.g. ACCOUNT_CLOSED, matched by account number)
    trigger_type        varchar(20)              not null,
    event_type          varchar(50),
    period_months       integer                  not null,
    disposition_action  varchar(20)              not null,
    requires_approval   boolean                  not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_retention_policy primary key (id),
    constraint uk_retention_policy_tenant_id unique (tenant_id, id),
    constraint uk_retention_policy_code unique (tenant_id, code),
    constraint fk_retention_policy_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_retention_policy_trigger check (trigger_type in ('CREATION_DATE', 'EXPIRY_DATE', 'EVENT')),
    constraint ck_retention_policy_action check (disposition_action in ('DESTROY', 'REVIEW')),
    constraint ck_retention_policy_period check (period_months >= 0),
    constraint ck_retention_policy_event check ((trigger_type = 'EVENT') = (event_type is not null))
);

alter table document_type add constraint fk_document_type_retention
    foreign key (tenant_id, retention_policy_id) references retention_policy (tenant_id, id);

create table legal_hold (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    name                varchar(200)             not null,
    reason              varchar(1000)            not null,
    status              varchar(20)              not null,
    released_by         uuid,
    released_at         timestamp with time zone,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_legal_hold primary key (id),
    constraint uk_legal_hold_tenant_id unique (tenant_id, id),
    constraint fk_legal_hold_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_legal_hold_status check (status in ('ACTIVE', 'RELEASED'))
);

create table legal_hold_item (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    hold_id             uuid                     not null,
    document_id         uuid                     not null,
    added_by            uuid                     not null,
    added_at            timestamp with time zone not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_legal_hold_item primary key (id),
    constraint uk_legal_hold_item unique (tenant_id, hold_id, document_id),
    constraint fk_legal_hold_item_hold foreign key (tenant_id, hold_id) references legal_hold (tenant_id, id),
    constraint fk_legal_hold_item_document foreign key (tenant_id, document_id) references document (tenant_id, id)
);
create index ix_legal_hold_item_document on legal_hold_item (tenant_id, document_id);

create table record_event (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    event_type          varchar(50)              not null,
    account_no          varchar(50)              not null,
    occurred_at         timestamp with time zone not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_record_event primary key (id),
    constraint fk_record_event_tenant foreign key (tenant_id) references tenant (id)
);
create index ix_record_event_account on record_event (tenant_id, event_type, account_no);

create table disposition_batch (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    -- PENDING (prepared, waiting for a records officer), EXECUTED, REJECTED
    status              varchar(20)              not null,
    prepared_at         timestamp with time zone not null,
    approved_by         uuid,
    approved_at         timestamp with time zone,
    executed_at         timestamp with time zone,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_disposition_batch primary key (id),
    constraint uk_disposition_batch_tenant_id unique (tenant_id, id),
    constraint fk_disposition_batch_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_disposition_batch_status check (status in ('PENDING', 'EXECUTED', 'REJECTED'))
);

create table disposition_item (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    batch_id            uuid                     not null,
    document_id         uuid                     not null,
    retention_policy_id uuid                     not null,
    due_date            date                     not null,
    -- PENDING, DISPOSED, SKIPPED (held or no longer eligible when executed), FAILED
    outcome             varchar(20)              not null,
    outcome_reason      varchar(500),
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_disposition_item primary key (id),
    constraint uk_disposition_item unique (tenant_id, batch_id, document_id),
    constraint fk_disposition_item_batch foreign key (tenant_id, batch_id) references disposition_batch (tenant_id, id),
    constraint fk_disposition_item_document foreign key (tenant_id, document_id) references document (tenant_id, id),
    constraint ck_disposition_item_outcome check (outcome in ('PENDING', 'DISPOSED', 'SKIPPED', 'FAILED'))
);
create index ix_disposition_item_document on disposition_item (tenant_id, document_id);

-- Evidence kept after disposition: what existed, when, and which approved batch removed it
create table tombstone (
    id                    uuid                     not null,
    tenant_id             uuid                     not null,
    document_id           uuid                     not null,
    document_type_id      uuid                     not null,
    document_created_at   timestamp with time zone not null,
    disposed_at           timestamp with time zone not null,
    disposition_batch_id  uuid                     not null,
    approved_by           uuid                     not null,
    sha256_list           jsonb                    not null,
    created_at            timestamp with time zone not null,
    created_by            varchar(64)              not null,
    updated_at            timestamp with time zone not null,
    updated_by            varchar(64)              not null,
    version_lock          bigint                   not null,
    constraint pk_tombstone primary key (id),
    constraint uk_tombstone_document unique (tenant_id, document_id),
    constraint fk_tombstone_batch foreign key (tenant_id, disposition_batch_id)
        references disposition_batch (tenant_id, id)
);
