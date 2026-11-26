-- B-01: tenants, branches and identity (docs/03-domain-model.md, modules tenant and identity).
-- Every tenant-owned table carries tenant_id; references between tenant-owned rows use composite foreign keys on
-- (tenant_id, id) so the database itself rejects a row that points at another tenant's data.

create table tenant (
    id              uuid                     not null,
    code            varchar(30)              not null,
    name            varchar(200)             not null,
    status          varchar(20)              not null,
    kms_key_ref     varchar(200),
    default_locale  varchar(10)              not null,
    settings        jsonb                    not null,
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_tenant primary key (id),
    constraint uk_tenant_code unique (code),
    constraint ck_tenant_status check (status in ('ACTIVE', 'SUSPENDED'))
);

create table branch (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    code            varchar(30)              not null,
    name            varchar(200)             not null,
    region          varchar(100),
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_branch primary key (id),
    constraint uk_branch_tenant_id unique (tenant_id, id),
    constraint uk_branch_tenant_code unique (tenant_id, code),
    constraint fk_branch_tenant foreign key (tenant_id) references tenant (id)
);

create table user_profile (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    username        varchar(255)             not null,
    display_name    varchar(255),
    email           varchar(255),
    home_branch_id  uuid,
    principal_type  varchar(10)              not null,
    branch_scope    varchar(10)              not null,
    clearance       varchar(20)              not null,
    status          varchar(20)              not null,
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_user_profile primary key (id),
    constraint uk_user_profile_tenant_id unique (tenant_id, id),
    constraint uk_user_profile_username unique (tenant_id, username),
    constraint fk_user_profile_tenant foreign key (tenant_id) references tenant (id),
    constraint fk_user_profile_home_branch foreign key (tenant_id, home_branch_id) references branch (tenant_id, id),
    constraint ck_user_profile_principal check (principal_type in ('USER', 'CLIENT')),
    constraint ck_user_profile_scope check (branch_scope in ('ALL', 'LIST')),
    constraint ck_user_profile_clearance check (clearance in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    constraint ck_user_profile_status check (status in ('ACTIVE', 'DISABLED'))
);

create table user_branch_scope (
    user_id         uuid                     not null,
    branch_id       uuid                     not null,
    tenant_id       uuid                     not null,
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_user_branch_scope primary key (user_id, branch_id),
    constraint fk_user_branch_scope_user foreign key (tenant_id, user_id) references user_profile (tenant_id, id),
    constraint fk_user_branch_scope_branch foreign key (tenant_id, branch_id) references branch (tenant_id, id)
);
create index ix_user_branch_scope_tenant on user_branch_scope (tenant_id, user_id);

create table role (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    code            varchar(50)              not null,
    name            varchar(200)             not null,
    description     varchar(500),
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_role primary key (id),
    constraint uk_role_tenant_id unique (tenant_id, id),
    constraint uk_role_tenant_code unique (tenant_id, code),
    constraint fk_role_tenant foreign key (tenant_id) references tenant (id)
);

create table user_role (
    user_id         uuid                     not null,
    role_id         uuid                     not null,
    tenant_id       uuid                     not null,
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_user_role primary key (user_id, role_id),
    constraint fk_user_role_user foreign key (tenant_id, user_id) references user_profile (tenant_id, id),
    constraint fk_user_role_role foreign key (tenant_id, role_id) references role (tenant_id, id)
);
create index ix_user_role_tenant_role on user_role (tenant_id, role_id);

create table delegation (
    id              uuid                     not null,
    tenant_id       uuid                     not null,
    from_user_id    uuid                     not null,
    to_user_id      uuid                     not null,
    role_id         uuid,
    valid_from      timestamp with time zone not null,
    valid_to        timestamp with time zone not null,
    reason          varchar(500)             not null,
    approved_by     uuid,
    created_at      timestamp with time zone not null,
    created_by      varchar(64)              not null,
    updated_at      timestamp with time zone not null,
    updated_by      varchar(64)              not null,
    version_lock    bigint                   not null,
    constraint pk_delegation primary key (id),
    constraint fk_delegation_from_user foreign key (tenant_id, from_user_id) references user_profile (tenant_id, id),
    constraint fk_delegation_to_user foreign key (tenant_id, to_user_id) references user_profile (tenant_id, id),
    constraint fk_delegation_role foreign key (tenant_id, role_id) references role (tenant_id, id),
    constraint fk_delegation_approved_by foreign key (tenant_id, approved_by) references user_profile (tenant_id, id),
    constraint ck_delegation_period check (valid_to > valid_from)
);
create index ix_delegation_tenant_to_user on delegation (tenant_id, to_user_id, valid_to);
