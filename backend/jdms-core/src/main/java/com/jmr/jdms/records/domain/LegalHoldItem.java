package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A document placed under a legal hold. */
@Entity
@Table(name = "legal_hold_item")
public class LegalHoldItem extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "added_by", nullable = false, updatable = false)
    private UUID addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    protected LegalHoldItem() {
    }

    public LegalHoldItem(UUID holdId, UUID documentId, UUID addedBy, Instant addedAt) {
        this.id = Ids.newId();
        this.holdId = holdId;
        this.documentId = documentId;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
