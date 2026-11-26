package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Bytes stored in a cabinet (every version), for the upload quota (B-13; was a sum over all versions). */
@Entity
@Table(name = "cabinet_usage")
public class CabinetUsage extends TenantScopedEntity {

    @Id
    @Column(name = "cabinet_id", nullable = false, updatable = false)
    private UUID cabinetId;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    protected CabinetUsage() {
    }

    public CabinetUsage(UUID cabinetId) {
        this.cabinetId = cabinetId;
    }

    long usedBytes() {
        return usedBytes;
    }

    void add(long bytes) {
        usedBytes += bytes;
    }

    void release(long bytes) {
        usedBytes = Math.max(0, usedBytes - bytes);
    }
}
