package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The identity facts taken from a validated access token. Branch scope, home branch and clearance are used only to
 * seed a new {@link UserProfile}; afterwards the profile is authoritative (ADR-0007).
 *
 * @param branchScopeCodes {@code ["ALL"]} or branch codes; empty means no branches
 * @param clearance        null when the token carries none or an unknown value (seeded as {@code PUBLIC})
 */
public record IdentityClaims(
        UUID subject,
        String tenantCode,
        String username,
        String displayName,
        String email,
        PrincipalType principalType,
        Set<String> roles,
        List<String> branchScopeCodes,
        String homeBranchCode,
        Classification clearance) {

    public static final String ALL_BRANCHES = "ALL";

    public IdentityClaims {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tenantCode, "tenantCode");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(principalType, "principalType");
        roles = Set.copyOf(roles);
        branchScopeCodes = List.copyOf(branchScopeCodes);
    }

    public boolean allBranches() {
        return branchScopeCodes.contains(ALL_BRANCHES);
    }
}
