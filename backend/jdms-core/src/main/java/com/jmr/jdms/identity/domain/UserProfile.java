package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * A user or bank system known to JDMS Next (legacy {@code CM_USERS}). The ID is the Keycloak subject. Identity fields
 * (username, display name, email) follow Keycloak; branch scope and clearance are owned here once the profile exists
 * (ADR-0007).
 */
@Entity
@Table(name = "user_profile")
public class UserProfile extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(length = 255)
    private String email;

    @Column(name = "home_branch_id")
    private UUID homeBranchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 10)
    private PrincipalType principalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_scope", nullable = false, length = 10)
    private BranchScopeType branchScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Classification clearance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    protected UserProfile() {
    }

    public UserProfile(UUID id, String username, String displayName, String email, PrincipalType principalType,
            UUID homeBranchId, BranchScopeType branchScope, Classification clearance) {
        this.id = Objects.requireNonNull(id);
        this.username = Objects.requireNonNull(username);
        this.displayName = displayName;
        this.email = email;
        this.principalType = Objects.requireNonNull(principalType);
        this.homeBranchId = homeBranchId;
        this.branchScope = Objects.requireNonNull(branchScope);
        this.clearance = Objects.requireNonNull(clearance);
        this.status = UserStatus.ACTIVE;
    }

    /** Applies identity fields from Keycloak; returns whether anything changed. */
    public boolean refreshIdentity(String newUsername, String newDisplayName, String newEmail) {
        boolean changed = !Objects.equals(username, newUsername) || !Objects.equals(displayName, newDisplayName)
                || !Objects.equals(email, newEmail);
        username = newUsername;
        displayName = newDisplayName;
        email = newEmail;
        return changed;
    }

    public void changeAccess(BranchScopeType newScope, Classification newClearance) {
        branchScope = Objects.requireNonNull(newScope);
        clearance = Objects.requireNonNull(newClearance);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public UUID getHomeBranchId() {
        return homeBranchId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public BranchScopeType getBranchScope() {
        return branchScope;
    }

    public Classification getClearance() {
        return clearance;
    }

    public UserStatus getStatus() {
        return status;
    }
}
