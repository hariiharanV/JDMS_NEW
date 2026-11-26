-- B-04: authorization rules and document grants (docs/03-domain-model.md, module access; spec §6).

create table access_rule (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    -- TENANT (scope_id = tenant), CABINET, DOCUMENT_TYPE, FOLDER (and its sub-folders) or DOCUMENT
    scope_type          varchar(20)              not null,
    scope_id            uuid                     not null,
    principal_type      varchar(10)              not null,
    -- role code for ROLE, user ID for USER
    principal_id        varchar(64)              not null,
    actions             jsonb                    not null,
    effect              varchar(10)              not null,
    -- OWN (the user's branch scope), ALL, or LIST with branch IDs in branch_ids
    branch_constraint   varchar(10)              not null,
    branch_ids          jsonb                    not null,
    valid_from          timestamp with time zone,
    valid_to            timestamp with time zone,
    approval_status     varchar(20)              not null,
    description         varchar(500),
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_access_rule primary key (id),
    constraint fk_access_rule_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_access_rule_scope check (scope_type in ('TENANT', 'CABINET', 'DOCUMENT_TYPE', 'FOLDER', 'DOCUMENT')),
    constraint ck_access_rule_principal check (principal_type in ('ROLE', 'USER')),
    constraint ck_access_rule_effect check (effect in ('ALLOW', 'DENY')),
    constraint ck_access_rule_branch check (branch_constraint in ('OWN', 'ALL', 'LIST')),
    constraint ck_access_rule_approval check (approval_status in ('APPROVED')),
    constraint ck_access_rule_validity check (valid_to is null or valid_from is null or valid_to > valid_from)
);
create index ix_access_rule_tenant on access_rule (tenant_id, principal_type, principal_id);

create table document_grant (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    document_id         uuid                     not null,
    user_id             uuid                     not null,
    actions             jsonb                    not null,
    valid_until         timestamp with time zone not null,
    source              varchar(30)              not null,
    source_id           uuid,
    revoked_at          timestamp with time zone,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_document_grant primary key (id),
    constraint fk_document_grant_document foreign key (tenant_id, document_id) references document (tenant_id, id),
    constraint fk_document_grant_user foreign key (tenant_id, user_id) references user_profile (tenant_id, id),
    constraint ck_document_grant_source check (source in ('RETRIEVAL_REQUEST', 'SHARE'))
);
create index ix_document_grant_user on document_grant (tenant_id, user_id, valid_until);
