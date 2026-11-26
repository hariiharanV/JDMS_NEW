-- B-13: running storage usage per cabinet for the upload quota check. Before, every upload summed the size of every
-- version in the cabinet (a join over all its versions: seconds per upload at a million documents, and a pool of
-- connections held meanwhile). The document module keeps this row in step, in the same transaction, whenever a
-- version is written or discarded; the upload's check locks the row, so concurrent uploads cannot both pass a full
-- quota. Usage counts every version, as the sum did.

create table cabinet_usage (
    tenant_id     uuid                     not null,
    cabinet_id    uuid                     not null,
    used_bytes    bigint                   not null,
    created_at    timestamp with time zone not null,
    created_by    varchar(64)              not null,
    updated_at    timestamp with time zone not null,
    updated_by    varchar(64)              not null,
    version_lock  bigint                   not null,
    constraint pk_cabinet_usage primary key (cabinet_id),
    constraint fk_cabinet_usage_cabinet foreign key (tenant_id, cabinet_id) references cabinet (tenant_id, id),
    constraint ck_cabinet_usage_bytes check (used_bytes >= 0)
);

insert into cabinet_usage (tenant_id, cabinet_id, used_bytes, created_at, created_by, updated_at, updated_by,
                           version_lock)
select c.tenant_id, c.id, coalesce(sum(v.size_bytes), 0), now(), 'migration', now(), 'migration', 0
  from cabinet c
  left join document d on d.tenant_id = c.tenant_id and d.cabinet_id = c.id
  left join document_version v on v.tenant_id = d.tenant_id and v.document_id = d.id
 group by c.tenant_id, c.id;
