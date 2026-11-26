-- B-02: metadata model (docs/03-domain-model.md, module metadata). Codes are unique per tenant; references between
-- tenant-owned rows are composite (tenant_id, id) foreign keys (ADR-0006). Rows are deactivated, never deleted,
-- once documents can refer to them; only empty folders and unused LOVs are removed.

create table cabinet (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    code                varchar(30)              not null,
    name                varchar(200)             not null,
    max_file_size_mb    integer                  not null,
    quota_mb            bigint                   not null,
    default_process_key varchar(100),
    status              varchar(20)              not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_cabinet primary key (id),
    constraint uk_cabinet_tenant_id unique (tenant_id, id),
    constraint uk_cabinet_tenant_code unique (tenant_id, code),
    constraint fk_cabinet_tenant foreign key (tenant_id) references tenant (id),
    constraint ck_cabinet_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint ck_cabinet_file_size check (max_file_size_mb between 1 and 500),
    constraint ck_cabinet_quota check (quota_mb >= max_file_size_mb)
);

-- Virtual folders: documents refer to them, storage never depends on them. path_cache is for display only.
create table folder (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    cabinet_id          uuid                     not null,
    parent_id           uuid,
    name                varchar(200)             not null,
    path_cache          varchar(4000)            not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_folder primary key (id),
    constraint uk_folder_tenant_id unique (tenant_id, id),
    -- NULLS NOT DISTINCT: two root folders of a cabinet cannot share a name (Oracle compares partial nulls this way)
    constraint uk_folder_sibling_name unique nulls not distinct (tenant_id, cabinet_id, parent_id, name),
    constraint fk_folder_cabinet foreign key (tenant_id, cabinet_id) references cabinet (tenant_id, id),
    constraint fk_folder_parent foreign key (tenant_id, parent_id) references folder (tenant_id, id)
);
create index ix_folder_parent on folder (tenant_id, cabinet_id, parent_id);

create table document_type (
    id                         uuid                     not null,
    tenant_id                  uuid                     not null,
    cabinet_id                 uuid                     not null,
    code                       varchar(30)              not null,
    name_en                    varchar(200)             not null,
    name_ar                    varchar(200)             not null,
    category                   varchar(50)              not null,
    channels                   jsonb                    not null,
    default_classification     varchar(20)              not null,
    -- foreign key to retention_policy is added with B-10
    retention_policy_id        uuid,
    process_key                varchar(100),
    meta_change_needs_approval boolean                  not null,
    delete_requires_approval   boolean                  not null,
    declare_record_on_approval boolean                  not null,
    required_standard_fields   jsonb                    not null,
    status                     varchar(20)              not null,
    created_at                 timestamp with time zone not null,
    created_by                 varchar(64)              not null,
    updated_at                 timestamp with time zone not null,
    updated_by                 varchar(64)              not null,
    version_lock               bigint                   not null,
    constraint pk_document_type primary key (id),
    constraint uk_document_type_tenant_id unique (tenant_id, id),
    constraint uk_document_type_code unique (tenant_id, code),
    constraint fk_document_type_cabinet foreign key (tenant_id, cabinet_id) references cabinet (tenant_id, id),
    constraint ck_document_type_class check (default_classification in
        ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    constraint ck_document_type_status check (status in ('ACTIVE', 'INACTIVE'))
);
create index ix_document_type_cabinet on document_type (tenant_id, cabinet_id);

create table field_definition (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    document_type_id    uuid                     not null,
    field_key           varchar(50)              not null,
    label_en            varchar(200)             not null,
    label_ar            varchar(200)             not null,
    data_type           varchar(10)              not null,
    mandatory           boolean                  not null,
    max_length          integer,
    default_value       varchar(4000),
    lov_code            varchar(30),
    searchable          boolean                  not null,
    facet               boolean                  not null,
    sensitive           boolean                  not null,
    display_order       integer                  not null,
    display_row         integer                  not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_field_definition primary key (id),
    constraint uk_field_definition_key unique (tenant_id, document_type_id, field_key),
    constraint fk_field_definition_type foreign key (tenant_id, document_type_id)
        references document_type (tenant_id, id),
    constraint ck_field_definition_type check (data_type in ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'LOV'))
);

create table lov (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    code                varchar(30)              not null,
    name                varchar(200)             not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_lov primary key (id),
    constraint uk_lov_tenant_id unique (tenant_id, id),
    constraint uk_lov_tenant_code unique (tenant_id, code),
    constraint fk_lov_tenant foreign key (tenant_id) references tenant (id)
);

create table lov_value (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    lov_id              uuid                     not null,
    code                varchar(50)              not null,
    label_en            varchar(200)             not null,
    label_ar            varchar(200)             not null,
    display_order       integer                  not null,
    active              boolean                  not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_lov_value primary key (id),
    constraint uk_lov_value_code unique (tenant_id, lov_id, code),
    constraint fk_lov_value_lov foreign key (tenant_id, lov_id) references lov (tenant_id, id)
);
