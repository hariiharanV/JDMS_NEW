package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A user's membership of a committee. Legacy CM_COMMITTEE_USER. */
@Entity
@Table(name = "committee_member")
public class CommitteeMember extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "committee_id", nullable = false, updatable = false)
    private UUID committeeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(length = 100)
    private String designation;

    protected CommitteeMember() {
    }

    public CommitteeMember(UUID committeeId, UUID userId, String designation) {
        this.id = Ids.newId();
        this.committeeId = committeeId;
        this.userId = userId;
        this.designation = designation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCommitteeId() {
        return committeeId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDesignation() {
        return designation;
    }
}
