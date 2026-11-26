package com.jmr.jdms.access.api;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import com.jmr.jdms.shared.context.RequestContext;
import java.util.Set;
import java.util.UUID;

/** Who asks: tenant, user, effective roles (own and delegated), branch scope and clearance. */
public record Subject(UUID tenantId, UUID userId, Set<String> roles, BranchScope branchScope,
        Classification clearance) {

    public Subject {
        roles = Set.copyOf(roles);
    }

    public static Subject of(RequestContext context) {
        return new Subject(context.tenantId(), context.subjectId(), context.roles(), context.branchScope(),
                context.clearance());
    }

    public static Subject current() {
        return of(RequestContext.require());
    }
}
