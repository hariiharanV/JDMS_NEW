package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** The content of an access rule, as proposed by security admins and stored once approved. */
public record RuleSpec(
        ScopeType scopeType,
        UUID scopeId,
        PrincipalKind principalType,
        String principalId,
        Set<Action> actions,
        Effect effect,
        BranchConstraint branchConstraint,
        Set<UUID> branchIds,
        Instant validFrom,
        Instant validTo,
        String description) {

    public RuleSpec {
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        branchIds = branchIds == null ? Set.of() : Set.copyOf(branchIds);
    }
}
