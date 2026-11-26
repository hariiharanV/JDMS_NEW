-- B-06: renditions, detected content type, notifications (docs/03-domain-model.md; spec §4.2, §4.5).

-- Content type detected by the worker from the content itself (the client-supplied mime_type is not trusted)
alter table document_version add column detected_mime_type varchar(150);

create table rendition (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    version_id          uuid                     not null,
    -- PDF (preview), THUMBNAIL (PNG), TEXT (extracted or OCR text, UTF-8); PDFA later
    kind                varchar(20)              not null,
    mime_type           varchar(100)             not null,
    blob_key            varchar(300)             not null,
    sha256              varchar(64)              not null,
    size_bytes          bigint                   not null,
    encrypted_data_key  varchar(500)             not null,
    key_ref             varchar(200)             not null,
    ocr                 boolean                  not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_rendition primary key (id),
    -- one rendition per kind: a duplicate delivery cannot add a second one
    constraint uk_rendition_kind unique (tenant_id, version_id, kind),
    constraint uk_rendition_blob unique (blob_key),
    constraint fk_rendition_version foreign key (tenant_id, version_id) references document_version (tenant_id, id),
    constraint ck_rendition_kind check (kind in ('PDF', 'THUMBNAIL', 'TEXT', 'PDFA')),
    constraint ck_rendition_size check (size_bytes > 0)
);

create table notification (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    user_id             uuid                     not null,
    -- e.g. VERSION_QUARANTINED
    type                varchar(50)              not null,
    document_id         uuid,
    version_id          uuid,
    message             varchar(500)             not null,
    read_at             timestamp with time zone,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_notification primary key (id),
    constraint fk_notification_user foreign key (tenant_id, user_id) references user_profile (tenant_id, id)
);
create index ix_notification_user on notification (tenant_id, user_id, created_at);
