package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** One branch in a user's {@code LIST} branch scope. */
@Entity
@Table(name = "user_branch_scope")
@IdClass(UserBranchScope.Key.class)
public class UserBranchScope extends TenantScopedEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    protected UserBranchScope() {
    }

    public UserBranchScope(UUID userId, UUID branchId) {
        this.userId = userId;
        this.branchId = branchId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public record Key(UUID userId, UUID branchId) implements Serializable {
    }
}
