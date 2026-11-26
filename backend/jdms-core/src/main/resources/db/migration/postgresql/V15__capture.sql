-- B-15 (ADR-0024): assisted indexing. A capture is a file a user attached on the upload screen, read by the worker
-- (antivirus, text, OCR) to suggest field values before the document exists. It is short-lived: the file's blob is
-- deleted once it has been read, and the row and its suggestions expire (an hour by default). Suggested values are
-- kept only in an encrypted blob, never in a column.

create table capture (
    id                  uuid                     not null,
    tenant_id           uuid                     not null,
    document_type_id    uuid                     not null,
    -- the placement the upload permission was checked for (re-checked when the capture is read)
    branch_id           uuid                     not null,
    folder_id           uuid,
    classification      varchar(20)              not null,
    -- PENDING (the worker is reading it), DONE, INFECTED, FAILED
    status              varchar(20)              not null,
    -- the attached file; null once the worker has read it
    blob_key            varchar(300),
    sha256              varchar(64),
    size_bytes          bigint                   not null,
    encrypted_data_key  varchar(500),
    key_ref             varchar(200),
    detected_mime_type  varchar(150),
    ocr                 boolean                  not null,
    suggestion_count    integer                  not null,
    -- the suggestions (JSON) as an encrypted blob written by the worker
    sugg_blob_key       varchar(300),
    sugg_sha256         varchar(64),
    sugg_size_bytes     bigint,
    sugg_data_key       varchar(500),
    sugg_key_ref        varchar(200),
    expires_at          timestamp with time zone not null,
    created_at          timestamp with time zone not null,
    created_by          varchar(64)              not null,
    updated_at          timestamp with time zone not null,
    updated_by          varchar(64)              not null,
    version_lock        bigint                   not null,
    constraint pk_capture primary key (id),
    constraint uk_capture_blob unique (blob_key),
    constraint uk_capture_sugg_blob unique (sugg_blob_key),
    constraint fk_capture_type foreign key (tenant_id, document_type_id) references document_type (tenant_id, id),
    constraint fk_capture_branch foreign key (tenant_id, branch_id) references branch (tenant_id, id),
    constraint ck_capture_status check (status in ('PENDING', 'DONE', 'INFECTED', 'FAILED')),
    constraint ck_capture_class check (classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);
create index ix_capture_expiry on capture (tenant_id, expires_at);
create index ix_capture_owner on capture (tenant_id, created_by, status);
