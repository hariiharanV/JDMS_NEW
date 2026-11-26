package com.jmr.jdms.access.domain;

import static com.jmr.jdms.access.api.Action.ADMIN_ACCESS;
import static com.jmr.jdms.access.api.Action.ADMIN_METADATA;
import static com.jmr.jdms.access.api.Action.APPROVE;
import static com.jmr.jdms.access.api.Action.CHECK_OUT;
import static com.jmr.jdms.access.api.Action.DELETE;
import static com.jmr.jdms.access.api.Action.DISPOSE;
import static com.jmr.jdms.access.api.Action.DOWNLOAD;
import static com.jmr.jdms.access.api.Action.EDIT_METADATA;
import static com.jmr.jdms.access.api.Action.IMPORT;
import static com.jmr.jdms.access.api.Action.MANAGE_HOLDS;
import static com.jmr.jdms.access.api.Action.MANAGE_RETENTION;
import static com.jmr.jdms.access.api.Action.RESTORE;
import static com.jmr.jdms.access.api.Action.UPLOAD;
import static com.jmr.jdms.access.api.Action.VIEW_AUDIT;
import static com.jmr.jdms.access.api.Action.VIEW_CONTENT;
import static com.jmr.jdms.access.api.Action.VIEW_METADATA;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.shared.security.Roles;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rules a new tenant starts with: the PoC actor matrix (spec §3) as tenant-wide role rules. Security admins
 * change them afterwards through maker-checker. Branch-bound roles see their own branches; head-office roles see all.
 */
final class BaselineRules {

    private record Grant(String role, Set<Action> actions, BranchConstraint branches) {
    }

    private static final List<Grant> MATRIX = List.of(
            new Grant(Roles.MAKER, Set.of(VIEW_METADATA, VIEW_CONTENT, DOWNLOAD, UPLOAD, EDIT_METADATA, CHECK_OUT,
                    DELETE), BranchConstraint.OWN),
            new Grant(Roles.CHECKER, Set.of(VIEW_METADATA, VIEW_CONTENT, DOWNLOAD, APPROVE), BranchConstraint.OWN),
            new Grant(Roles.VIEWER, Set.of(VIEW_METADATA, VIEW_CONTENT), BranchConstraint.OWN),
            new Grant(Roles.COMMITTEE_MEMBER, Set.of(VIEW_METADATA, VIEW_CONTENT, APPROVE), BranchConstraint.ALL),
            new Grant(Roles.RECORDS_OFFICER, Set.of(VIEW_METADATA, VIEW_CONTENT, DOWNLOAD, RESTORE, MANAGE_HOLDS,
                    MANAGE_RETENTION, DISPOSE), BranchConstraint.ALL),
            new Grant(Roles.INTEGRATION_CLIENT, Set.of(VIEW_METADATA, VIEW_CONTENT, DOWNLOAD, UPLOAD, EDIT_METADATA,
                    CHECK_OUT), BranchConstraint.ALL),
            new Grant(Roles.TENANT_ADMIN, Set.of(ADMIN_METADATA), BranchConstraint.ALL),
            new Grant(Roles.SECURITY_ADMIN, Set.of(ADMIN_ACCESS, VIEW_AUDIT), BranchConstraint.ALL),
            // reads back what it imported, for the reconciliation report (ADR-0020)
            new Grant(Roles.MIGRATION, Set.of(IMPORT, VIEW_METADATA), BranchConstraint.ALL));

    private BaselineRules() {
    }

    static List<RuleSpec> forTenant(UUID tenantId) {
        return MATRIX.stream().map(grant -> new RuleSpec(ScopeType.TENANT, tenantId, PrincipalKind.ROLE, grant.role(),
                grant.actions(), Effect.ALLOW, grant.branches(), Set.of(), null, null,
                "Baseline: " + grant.role())).toList();
    }
}
