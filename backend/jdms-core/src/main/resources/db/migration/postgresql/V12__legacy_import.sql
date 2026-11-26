-- B-12: documents imported from legacy JDMS (ADR-0020). The legacy creation time and user are kept next to the
-- audit columns (which record the import itself); a legacy document can be imported once per tenant.

alter table document add column legacy_created_at timestamp with time zone;
alter table document add column legacy_created_by varchar(100);
alter table document_version add column legacy_created_at timestamp with time zone;
alter table document_version add column legacy_created_by varchar(100);

drop index ix_document_legacy;
alter table document add constraint uq_document_legacy unique (tenant_id, legacy_document_id);

-- The legacy origin of a version is part of its immutable record (V6).
create or replace function document_version_immutable() returns trigger as $$
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
        or new.created_at <> old.created_at or new.created_by <> old.created_by
        or new.legacy_created_at is distinct from old.legacy_created_at
        or new.legacy_created_by is distinct from old.legacy_created_by then
        raise exception 'document_version % is immutable', old.id using errcode = 'P0001';
    end if;
    return new;
end;
$$ language plpgsql;
