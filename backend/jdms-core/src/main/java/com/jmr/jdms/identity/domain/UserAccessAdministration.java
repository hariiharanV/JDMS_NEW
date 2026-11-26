package com.jmr.jdms.identity.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administration of users' branch scope and clearance, with maker-checker: a security admin proposes, a second
 * security admin approves. Nobody can propose a change to their own access, and the user whose access changes can
 * never approve it (spec §6 separation of duties). Callers are authorised by the web layer.
 */
@Service
public class UserAccessAdministration implements ChangeHandler {

    public static final String SUBJECT = "USER_ACCESS";

    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String USER_SELF_CHANGE = "USER_SELF_CHANGE";
    public static final String BRANCH_UNKNOWN = "BRANCH_UNKNOWN";

    private final UserProfileRepository profiles;
    private final UserBranchScopeRepository branchScopes;
    private final BranchDirectory branchDirectory;
    private final AuditTrail auditTrail;
    private final ChangeRequests changeRequests;
    private final JsonMapper jsonMapper;
    private final AccessPolicy accessPolicy;
    private final UserRoles userRoles;

    UserAccessAdministration(UserProfileRepository profiles, UserBranchScopeRepository branchScopes,
            BranchDirectory branchDirectory, AuditTrail auditTrail, ChangeRequests changeRequests,
            JsonMapper jsonMapper, AccessPolicy accessPolicy, UserRoles userRoles) {
        this.profiles = profiles;
        this.branchScopes = branchScopes;
        this.branchDirectory = branchDirectory;
        this.auditTrail = auditTrail;
        this.changeRequests = changeRequests;
        this.jsonMapper = jsonMapper;
        this.accessPolicy = accessPolicy;
        this.userRoles = userRoles;
    }

    @Transactional(readOnly = true)
    public Page<UserAccessView> list(Pageable pageable) {
        return profiles.findAll(pageable).map(this::view);
    }

    /** Users of other tenants are reported as not found: they do not exist from the caller's point of view. */
    @Transactional(readOnly = true)
    public UserAccessView get(UUID userId) {
        return view(load(userId));
    }

    /** Proposes a change; it applies only when a second security admin approves it. */
    @Transactional
    public PendingChangeView proposeAccessChange(UUID userId, long expectedVersion, AccessChange change) {
        if (RequestContext.require().subjectId().equals(userId)) {
            throw ApiException.forbidden(USER_SELF_CHANGE, "You cannot change your own branch scope or clearance.");
        }
        if (change.allBranches() && !change.branchCodes().isEmpty()) {
            throw ApiException.unprocessable(ErrorCodes.VALIDATION_FAILED,
                    "Give either all branches or a list of branch codes, not both.");
        }
        UserProfile profile = load(userId);
        if (profile.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        Set<String> unknown = unknownBranches(change);
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable(BRANCH_UNKNOWN, "Unknown branch code(s): " + String.join(", ",
                    unknown.stream().sorted().toList()));
        }
        AccessChange normalised = new AccessChange(change.allBranches(),
                change.branchCodes().stream().distinct().sorted().toList(), change.clearance());
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, userId, null,
                expectedVersion, normalised, "Change access of " + profile.getUsername() + " to "
                        + (normalised.allBranches() ? "all branches" : "branches " + normalised.branchCodes())
                        + ", clearance " + normalised.clearance()));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.SECURITY_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        Subject subject = Subject.current();
        return accessPolicy.decide(subject, Action.ADMIN_ACCESS, new ResourceRef.Tenant(subject.tenantId()))
                .allowed();
    }

    /** The user whose access changes never approves it (spec §6: nobody approves their own permission change). */
    @Override
    public Optional<String> approvalBlocker(PendingChangeView change, RequestContext approver) {
        return approver.subjectId().equals(change.subjectId())
                ? Optional.of("You cannot approve a change to your own access.") : Optional.empty();
    }

    @Override
    public ApplyResult apply(PendingChangeView pending) {
        UserProfile profile = profiles.findById(pending.subjectId()).orElse(null);
        if (profile == null || profile.getVersionLock() != pending.baseVersion()) {
            return ApplyResult.stale("The user was changed after this change was proposed.");
        }
        AccessChange change = jsonMapper.treeToValue(pending.payload(), AccessChange.class);
        if (!unknownBranches(change).isEmpty()) {
            return ApplyResult.stale("A proposed branch no longer exists.");
        }
        UUID userId = profile.getId();
        List<BranchInfo> newBranches = change.allBranches() ? List.of()
                : branchDirectory.findByCodes(change.branchCodes());

        UserAccessView before = view(profile);
        profile.changeAccess(change.allBranches() ? BranchScopeType.ALL : BranchScopeType.LIST, change.clearance());
        List<UserBranchScope> current = branchScopes.findByUserId(userId);
        Set<UUID> wanted = newBranches.stream().map(BranchInfo::id).collect(Collectors.toSet());
        current.stream().filter(scope -> !wanted.contains(scope.getBranchId())).forEach(branchScopes::delete);
        Set<UUID> kept = current.stream().map(UserBranchScope::getBranchId).collect(Collectors.toSet());
        wanted.stream().filter(id -> !kept.contains(id))
                .forEach(id -> branchScopes.save(new UserBranchScope(userId, id)));
        profiles.saveAndFlush(profile);

        UserAccessView after = view(profile);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changeId", pending.id().toString());
        details.put("proposedBy", pending.proposedBy().toString());
        details.put("before", accessSummary(before));
        details.put("after", accessSummary(after));
        auditTrail.record(AuditEntry.success(IdentityAuditActions.ACCESS_CHANGED, IdentityAuditActions.RESOURCE_USER,
                userId, details));
        return ApplyResult.ok();
    }

    private Set<String> unknownBranches(AccessChange change) {
        if (change.allBranches()) {
            return Set.of();
        }
        Set<String> unknown = new HashSet<>(change.branchCodes());
        branchDirectory.findByCodes(change.branchCodes()).forEach(branch -> unknown.remove(branch.code()));
        return unknown;
    }

    private UserProfile load(UUID userId) {
        return profiles.findById(userId)
                .orElseThrow(() -> ApiException.notFound(USER_NOT_FOUND, "User not found."));
    }

    private UserAccessView view(UserProfile profile) {
        List<UUID> branchIds = new ArrayList<>();
        if (profile.getHomeBranchId() != null) {
            branchIds.add(profile.getHomeBranchId());
        }
        List<UUID> scopeIds = profile.getBranchScope() == BranchScopeType.ALL ? List.of()
                : branchScopes.findByUserId(profile.getId()).stream().map(UserBranchScope::getBranchId).toList();
        branchIds.addAll(scopeIds);
        Map<UUID, String> codes = branchDirectory.findByIds(branchIds).stream()
                .collect(Collectors.toMap(BranchInfo::id, BranchInfo::code, (a, b) -> a));
        return new UserAccessView(profile.getId(), profile.getUsername(), profile.getDisplayName(),
                profile.getEmail(), profile.getPrincipalType(),
                profile.getHomeBranchId() == null ? null : codes.get(profile.getHomeBranchId()),
                profile.getBranchScope() == BranchScopeType.ALL,
                scopeIds.stream().map(codes::get).filter(Objects::nonNull).sorted().toList(),
                profile.getClearance(), profile.getStatus(), userRoles.of(profile.getId()), profile.getVersionLock());
    }

    private static Map<String, Object> accessSummary(UserAccessView view) {
        return Map.of("branchScope", view.allBranches() ? (Object) IdentityClaims.ALL_BRANCHES : view.branchCodes(),
                "clearance", view.clearance().name());
    }
}
