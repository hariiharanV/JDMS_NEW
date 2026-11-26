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

/** A named legal hold: its documents cannot be deleted or disposed while it is active (spec §4.8). */
@Entity
@Table(name = "legal_hold")
public class LegalHold extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LegalHoldStatus status;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected LegalHold() {
    }

    public LegalHold(String name, String reason) {
        this.id = Ids.newId();
        this.name = name;
        this.reason = reason;
        this.status = LegalHoldStatus.ACTIVE;
    }

    void release(UUID by, Instant at) {
        this.status = LegalHoldStatus.RELEASED;
        this.releasedBy = by;
        this.releasedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getReason() {
        return reason;
    }

    public LegalHoldStatus getStatus() {
        return status;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
