-- B-02: maker-checker for administrative changes (docs/03-domain-model.md, module workflow). A proposed change is
-- stored here and applied only when a second, authorised user approves it. B-05 links changes to Flowable process
-- instances (process_instance_id).

create table pending_change (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    subject_type        varchar(30)              not null,
    operation           varchar(20)              not null,
    subject_id          uuid                     not null,
    -- natural key the change claims (e.g. a code being created), so two pending creates cannot claim the same code
    subject_key         varchar(300),
    -- version_lock of the subject when proposed; the change goes STALE if the subject changed before approval
    base_version        bigint,
    payload             jsonb                    not null,
    summary             varchar(500)             not null,
    status              varchar(20)              not null,
    proposed_by         uuid                     not null,
    proposed_at         timestamp with time zone not null,
    decided_by          uuid,
    decided_at          timestamp with time zone,
    decision_comment    varchar(1000),
    process_instance_id varchar(64),
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_pending_change primary key (id),
    constraint fk_pending_change_tenant foreign key (tenant_id) references tenant (id),
    constraint fk_pending_change_proposer foreign key (tenant_id, proposed_by) references user_profile (tenant_id, id),
    constraint fk_pending_change_decider foreign key (tenant_id, decided_by) references user_profile (tenant_id, id),
    constraint ck_pending_change_operation check (operation in ('CREATE', 'UPDATE', 'DEACTIVATE', 'DELETE')),
    constraint ck_pending_change_status check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'STALE'))
);
create index ix_pending_change_status on pending_change (tenant_id, status, proposed_at);

-- At most one pending change per subject, and per claimed natural key. Partial unique indexes are PostgreSQL
-- syntax; the Oracle script uses function-based unique indexes for the same rule.
create unique index ux_pending_change_subject on pending_change (tenant_id, subject_type, subject_id)
    where status = 'PENDING';
create unique index ux_pending_change_key on pending_change (tenant_id, subject_type, subject_key)
    where status = 'PENDING' and subject_key is not null;
