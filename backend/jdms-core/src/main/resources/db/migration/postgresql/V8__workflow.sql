-- B-05: approval history, committees (docs/03-domain-model.md, module workflow; spec §4.4).
-- Process and task state lives in Flowable's own ACT_* tables (ADR-0013).

create table approval_record (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    document_id         uuid,
    version_id          uuid,
    -- DOCUMENT, VERSION, METADATA_CHANGE, ACCESS_RULE, ADMIN_CHANGE (later: DELETE, RETRIEVAL, DISPOSITION)
    subject_type        varchar(30)              not null,
    subject_id          uuid                     not null,
    process_instance_id varchar(64)              not null,
    -- the Flowable task decided; null for submissions. Unique: a task is decided once, even when retried
    task_id             varchar(64),
    -- 1 for the first submission, +1 for each resubmission after a return
    round_no            integer                  not null,
    stage_key           varchar(100),
    stage_name          varchar(200),
    actor_id            uuid                     not null,
    actor_name          varchar(200)             not null,
    decision            varchar(20)              not null,
    decision_comment    varchar(2000),
    decided_at          timestamp with time zone not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_approval_record primary key (id),
    constraint uk_approval_record_task unique (task_id),
    constraint fk_approval_record_tenant foreign key (tenant_id) references tenant (id),
    constraint fk_approval_record_document foreign key (tenant_id, document_id) references document (tenant_id, id),
    constraint ck_approval_record_decision check (decision in ('SUBMIT', 'APPROVE', 'REJECT', 'RETURN',
        'VOTE_APPROVE', 'VOTE_REJECT')),
    constraint ck_approval_record_round check (round_no >= 1)
);
create index ix_approval_record_document on approval_record (tenant_id, document_id, decided_at);
create index ix_approval_record_process on approval_record (tenant_id, process_instance_id);

create table committee (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    code                varchar(50)              not null,
    name                varchar(200)             not null,
    min_quorum          integer                  not null,
    status              varchar(20)              not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_committee primary key (id),
    constraint uk_committee_tenant_id unique (tenant_id, id),
    constraint uk_committee_code unique (tenant_id, code),
    constraint fk_committee_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_committee_quorum check (min_quorum >= 1),
    constraint ck_committee_status check (status in ('ACTIVE', 'INACTIVE'))
);

create table committee_member (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    committee_id        uuid                     not null,
    user_id             uuid                     not null,
    designation         varchar(100),
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_committee_member primary key (id),
    constraint uk_committee_member unique (tenant_id, committee_id, user_id),
    constraint fk_committee_member_committee foreign key (tenant_id, committee_id) references committee (tenant_id, id),
    constraint fk_committee_member_user foreign key (tenant_id, user_id) references user_profile (tenant_id, id)
);
