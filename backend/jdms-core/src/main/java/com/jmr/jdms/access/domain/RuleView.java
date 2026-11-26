package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An access rule in API responses, with a readable scope label and branch codes. */
public record RuleView(
        UUID id,
        ScopeType scopeType,
        UUID scopeId,
        String scopeLabel,
        PrincipalKind principalType,
        String principalId,
        List<Action> actions,
        Effect effect,
        BranchConstraint branchConstraint,
        List<String> branchCodes,
        Instant validFrom,
        Instant validTo,
        String description,
        long version) {
}
