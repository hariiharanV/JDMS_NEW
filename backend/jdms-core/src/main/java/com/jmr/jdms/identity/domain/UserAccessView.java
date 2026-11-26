package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import java.util.List;
import java.util.UUID;

/** A user profile as administrators see it; branch scope as codes; roles as last mirrored from the token. */
public record UserAccessView(
        UUID id,
        String username,
        String displayName,
        String email,
        PrincipalType principalType,
        String homeBranchCode,
        boolean allBranches,
        List<String> branchCodes,
        Classification clearance,
        UserStatus status,
        List<String> roles,
        long version) {
}
