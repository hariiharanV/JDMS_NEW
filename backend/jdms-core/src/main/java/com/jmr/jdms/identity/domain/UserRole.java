package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** A role held by a user (legacy {@code CM_USERROLES}), mirrored from the token on login. */
@Entity
@Table(name = "user_role")
@IdClass(UserRole.Key.class)
public class UserRole extends TenantScopedEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    protected UserRole() {
    }

    public UserRole(UUID userId, UUID roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public record Key(UUID userId, UUID roleId) implements Serializable {
    }
}
