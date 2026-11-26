package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import java.util.Set;
import java.util.UUID;

/** What authentication needs from the caller's profile after synchronisation, including delegated roles. */
public record SyncedProfile(UUID userId, UserStatus status, BranchScope branchScope, Classification clearance,
        Set<String> delegatedRoles) {
}
