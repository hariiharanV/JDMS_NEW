-- B-18: bank systems find documents by their own reference (integration API GET /documents?externalRef=).
create index ix_document_external_ref on document (tenant_id, external_ref);
