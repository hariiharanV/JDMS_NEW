package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import java.util.List;
import java.util.UUID;

/** The authenticated caller, for {@code GET /api/v1/me}. */
public record CurrentUserView(
        String tenantCode,
        UUID userId,
        String username,
        String displayName,
        PrincipalType principalType,
        List<String> roles,
        List<String> delegatedRoles,
        boolean allBranches,
        List<String> branchCodes,
        Classification clearance,
        /** the languages the tenant's screens offer, English first (ADR-0023) */
        List<String> languages,
        String requestId) {
}
