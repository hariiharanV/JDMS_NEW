package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** An approved access rule in the form the policy engine evaluates. */
public record CompiledRule(
        UUID id,
        ScopeType scopeType,
        UUID scopeId,
        PrincipalKind principalKind,
        String principalId,
        Set<Action> actions,
        Effect effect,
        BranchConstraint branchConstraint,
        Set<UUID> branchIds,
        Instant validFrom,
        Instant validTo) {

    public CompiledRule {
        actions = Set.copyOf(actions);
        branchIds = Set.copyOf(branchIds);
    }

    boolean activeAt(Instant now) {
        return (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || now.isBefore(validTo));
    }
}
