package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-bound delegation of roles from one user to another. The rules (never more than the delegator holds, no
 * re-delegation) are enforced by the access module in B-04; B-01 provides the table.
 */
@Entity
@Table(name = "delegation")
public class Delegation extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    /** Null means all delegable roles of the delegator. */
    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "approved_by")
    private UUID approvedBy;

    protected Delegation() {
    }

    public Delegation(UUID fromUserId, UUID toUserId, UUID roleId, Instant validFrom, Instant validTo, String reason) {
        if (!validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        this.id = Ids.newId();
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.roleId = roleId;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.reason = reason;
    }

    public void approve(UUID approver) {
        this.approvedBy = approver;
    }

    /** Ends the delegation now (if still running); revocation needs no approval, it only restricts. */
    public void revoke(Instant now) {
        if (validTo.isAfter(now)) {
            validTo = now.isBefore(validFrom) ? validFrom.plusNanos(1000) : now;
        }
    }

    public boolean activeAt(Instant now) {
        return approvedBy != null && !now.isBefore(validFrom) && now.isBefore(validTo);
    }

    public UUID getId() {
        return id;
    }

    public UUID getFromUserId() {
        return fromUserId;
    }

    public UUID getToUserId() {
        return toUserId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public String getReason() {
        return reason;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }
}
