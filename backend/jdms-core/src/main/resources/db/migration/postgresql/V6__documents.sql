-- B-03: documents, immutable versions, processing jobs, idempotency keys and the transactional outbox
-- (docs/03-domain-model.md, modules document and shared infrastructure).

create table document (
    id                   uuid                     not null,
    tenant_id            uuid                     not null,
    cabinet_id           uuid                     not null,
    document_type_id     uuid                     not null,
    folder_id            uuid,
    title                varchar(500)             not null,
    status               varchar(20)              not null,
    classification       varchar(20)              not null,
    branch_id            uuid                     not null,
    cif                  varchar(50),
    customer_name        varchar(200),
    account_no           varchar(50),
    collateral_code      varchar(50),
    contract_ref         varchar(50),
    transaction_ref      varchar(50),
    application_ref      varchar(50),
    expiry_date          date,
    physical_location    varchar(200),
    physical_ref         varchar(100),
    custom_fields        jsonb                    not null,
    current_version_id   uuid,
    checked_out_by       uuid,
    checked_out_at       timestamp with time zone,
    record_declared_at   timestamp with time zone,
    retention_policy_id  uuid,
    retention_until      date,
    on_legal_hold        boolean                  not null,
    source_system        varchar(100)             not null,
    external_ref         varchar(100),
    legacy_document_id   varchar(100),
    deleted_at           timestamp with time zone,
    deleted_by           uuid,
    status_before_delete varchar(20),
    created_at           timestamp with time zone not null,
    created_by           varchar(64)              not null,
    updated_at           timestamp with time zone not null,
    updated_by           varchar(64)              not null,
    version_lock         bigint                   not null,
    constraint pk_document primary key (id),
    constraint uk_document_tenant_id unique (tenant_id, id),
    constraint fk_document_cabinet foreign key (tenant_id, cabinet_id) references cabinet (tenant_id, id),
    constraint fk_document_type foreign key (tenant_id, document_type_id) references document_type (tenant_id, id),
    constraint fk_document_folder foreign key (tenant_id, folder_id) references folder (tenant_id, id),
    constraint fk_document_branch foreign key (tenant_id, branch_id) references branch (tenant_id, id),
    constraint fk_document_checked_out foreign key (tenant_id, checked_out_by) references user_profile (tenant_id, id),
    constraint ck_document_status check (status in
        ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'DELETED', 'DISPOSED')),
    constraint ck_document_class check (classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);
create index ix_document_type on document (tenant_id, document_type_id);
create index ix_document_folder on document (tenant_id, folder_id);
create index ix_document_cif on document (tenant_id, cif);
create index ix_document_account on document (tenant_id, account_no);
create index ix_document_contract on document (tenant_id, contract_ref);
create index ix_document_collateral on document (tenant_id, collateral_code);
create index ix_document_transaction on document (tenant_id, transaction_ref);
create index ix_document_application on document (tenant_id, application_ref);
create index ix_document_legacy on document (tenant_id, legacy_document_id);

create table document_version (
    id                   uuid                     not null,
    tenant_id            uuid                     not null,
    document_id          uuid                     not null,
    major                integer                  not null,
    minor                integer                  not null,
    approval_status      varchar(20)              not null,
    blob_key             varchar(300)             not null,
    sha256               varchar(64)              not null,
    size_bytes           bigint                   not null,
    mime_type            varchar(150)             not null,
    original_filename    varchar(255),
    page_count           integer,
    version_comment      varchar(1000),
    storage_status       varchar(20)              not null,
    processing_status    varchar(20)              not null,
    index_status         varchar(20)              not null,
    encrypted_data_key   varchar(500)             not null,
    key_ref              varchar(200)             not null,
    created_at           timestamp with time zone not null,
    created_by           varchar(64)              not null,
    updated_at           timestamp with time zone not null,
    updated_by           varchar(64)              not null,
    version_lock         bigint                   not null,
    constraint pk_document_version primary key (id),
    constraint uk_document_version_tenant_id unique (tenant_id, id),
    constraint uk_document_version_number unique (document_id, major, minor),
    constraint uk_document_version_blob unique (blob_key),
    constraint fk_document_version_document foreign key (tenant_id, document_id) references document (tenant_id, id),
    constraint ck_document_version_approval check (approval_status in ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_document_version_storage check (storage_status in ('STORED', 'MISSING', 'QUARANTINED', 'DESTROYED')),
    constraint ck_document_version_processing check (processing_status in
        ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'QUARANTINED')),
    constraint ck_document_version_index check (index_status in ('PENDING', 'INDEXED', 'FAILED')),
    constraint ck_document_version_size check (size_bytes > 0)
);
create index ix_document_version_document on document_version (tenant_id, document_id);
create index ix_document_version_sha on document_version (tenant_id, sha256);

alter table document add constraint fk_document_current_version
    foreign key (tenant_id, current_version_id) references document_version (tenant_id, id);

-- Versions are immutable (spec §4.3): their content columns can never change, and only drafts that were never
-- checked in may be deleted (cancel check-out). Status columns (approval, storage, processing, index) do change.
-- PostgreSQL trigger; the Oracle scripts carry an equivalent trigger.
create function document_version_immutable() returns trigger as $$
begin
    if tg_op = 'DELETE' then
        if old.approval_status <> 'DRAFT' then
            raise exception 'document_version % is immutable: only drafts can be deleted', old.id
                using errcode = 'P0001';
        end if;
        return old;
    end if;
    if new.id <> old.id or new.tenant_id <> old.tenant_id or new.document_id <> old.document_id
        or new.major <> old.major or new.minor <> old.minor or new.blob_key <> old.blob_key
        or new.sha256 <> old.sha256 or new.size_bytes <> old.size_bytes or new.mime_type <> old.mime_type
        or new.original_filename is distinct from old.original_filename
        or new.version_comment is distinct from old.version_comment
        or new.encrypted_data_key <> old.encrypted_data_key or new.key_ref <> old.key_ref
        or new.created_at <> old.created_at or new.created_by <> old.created_by then
        raise exception 'document_version % is immutable', old.id using errcode = 'P0001';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_document_version_immutable
    before update or delete on document_version
    for each row execute function document_version_immutable();

-- Background work for the worker (B-06) and core jobs; polled with SELECT ... FOR UPDATE SKIP LOCKED.
create table job (
    id                   uuid                     not null,
    tenant_id            uuid                     not null,
    type                 varchar(30)              not null,
    payload              jsonb                    not null,
    status               varchar(20)              not null,
    attempts             integer                  not null,
    max_attempts         integer                  not null,
    run_after            timestamp with time zone not null,
    locked_by            varchar(100),
    locked_until         timestamp with time zone,
    last_error           varchar(2000),
    created_at           timestamp with time zone not null,
    created_by           varchar(64)              not null,
    updated_at           timestamp with time zone not null,
    updated_by           varchar(64)              not null,
    version_lock         bigint                   not null,
    constraint pk_job primary key (id),
    constraint fk_job_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_job_status check (status in ('READY', 'RUNNING', 'DONE', 'FAILED', 'DEAD'))
);
create index ix_job_ready on job (status, run_after);

-- Replay protection for write requests: same caller + key + payload returns the original response for 24 hours.
create table idempotency_key (
    id                   uuid                     not null,
    tenant_id            uuid                     not null,
    client_id            varchar(64)              not null,
    idem_key             varchar(100)             not null,
    request_hash         varchar(64)              not null,
    response_status      integer                  not null,
    response             jsonb                    not null,
    expires_at           timestamp with time zone not null,
    created_at           timestamp with time zone not null,
    created_by           varchar(64)              not null,
    updated_at           timestamp with time zone not null,
    updated_by           varchar(64)              not null,
    version_lock         bigint                   not null,
    constraint pk_idempotency_key primary key (id),
    constraint uk_idempotency_key unique (tenant_id, client_id, idem_key),
    constraint fk_idempotency_key_tenant foreign key (tenant_id) references tenant (id)
);
create index ix_idempotency_key_expiry on idempotency_key (expires_at);

-- Spring Modulith event publication registry (transactional outbox, D-10); schema as published by Spring Modulith 2.1.
create table event_publication (
    id                     uuid                     not null,
    listener_id            text                     not null,
    event_type             text                     not null,
    serialized_event       text                     not null,
    publication_date       timestamp with time zone not null,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    constraint pk_event_publication primary key (id)
);
create index ix_event_publication_done on event_publication (completion_date);
