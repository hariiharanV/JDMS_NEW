package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** One document in a disposition batch and what happened to it. */
@Entity
@Table(name = "disposition_item")
public class DispositionItem extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "retention_policy_id", nullable = false, updatable = false)
    private UUID retentionPolicyId;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemOutcome outcome;

    @Column(name = "outcome_reason", length = 500)
    private String outcomeReason;

    protected DispositionItem() {
    }

    public DispositionItem(UUID batchId, UUID documentId, UUID retentionPolicyId, LocalDate dueDate) {
        this.id = Ids.newId();
        this.batchId = batchId;
        this.documentId = documentId;
        this.retentionPolicyId = retentionPolicyId;
        this.dueDate = dueDate;
        this.outcome = ItemOutcome.PENDING;
    }

    void settle(ItemOutcome outcome, String reason) {
        this.outcome = outcome;
        this.outcomeReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getRetentionPolicyId() {
        return retentionPolicyId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public ItemOutcome getOutcome() {
        return outcome;
    }

    public String getOutcomeReason() {
        return outcomeReason;
    }
}
