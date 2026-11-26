package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** An access rule as proposed through the API; branches by code. */
public record RuleInput(
        ScopeType scopeType,
        UUID scopeId,
        PrincipalKind principalType,
        String principalId,
        Set<Action> actions,
        Effect effect,
        BranchConstraint branchConstraint,
        List<String> branchCodes,
        Instant validFrom,
        Instant validTo,
        String description) {
}
