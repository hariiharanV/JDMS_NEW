package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Evidence of a disposed document: identity, type, dates, approval reference and content hashes. Never changed. */
@Entity
@Table(name = "tombstone")
public class Tombstone extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Column(name = "document_created_at", nullable = false, updatable = false)
    private Instant documentCreatedAt;

    @Column(name = "disposed_at", nullable = false, updatable = false)
    private Instant disposedAt;

    @Column(name = "disposition_batch_id", nullable = false, updatable = false)
    private UUID dispositionBatchId;

    @Column(name = "approved_by", nullable = false, updatable = false)
    private UUID approvedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sha256_list", nullable = false, updatable = false)
    private List<String> sha256List;

    protected Tombstone() {
    }

    public Tombstone(UUID documentId, UUID documentTypeId, Instant documentCreatedAt, Instant disposedAt,
            UUID dispositionBatchId, UUID approvedBy, List<String> sha256List) {
        this.id = Ids.newId();
        this.documentId = documentId;
        this.documentTypeId = documentTypeId;
        this.documentCreatedAt = documentCreatedAt;
        this.disposedAt = disposedAt;
        this.dispositionBatchId = dispositionBatchId;
        this.approvedBy = approvedBy;
        this.sha256List = List.copyOf(sha256List);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public Instant getDocumentCreatedAt() {
        return documentCreatedAt;
    }

    public Instant getDisposedAt() {
        return disposedAt;
    }

    public UUID getDispositionBatchId() {
        return dispositionBatchId;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public List<String> getSha256List() {
        return sha256List;
    }
}
