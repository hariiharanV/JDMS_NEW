package com.jmr.jdms.records.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Documents due for disposition, prepared by the daily job and approved (or rejected) by a records officer. */
@Entity
@Table(name = "disposition_batch")
public class DispositionBatch extends TenantScopedEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private Instant preparedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected DispositionBatch() {
    }

    public DispositionBatch(Instant preparedAt) {
        this.id = Ids.newId();
        this.status = BatchStatus.PENDING;
        this.preparedAt = preparedAt;
    }

    void approve(UUID by, Instant at) {
        this.approvedBy = by;
        this.approvedAt = at;
    }

    void executed(Instant at) {
        this.status = BatchStatus.EXECUTED;
        this.executedAt = at;
    }

    void reject(UUID by, Instant at) {
        this.status = BatchStatus.REJECTED;
        this.approvedBy = by;
        this.approvedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public Instant getPreparedAt() {
        return preparedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
