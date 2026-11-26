package com.jmr.jdms.identity.domain;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.BranchScope;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code user_profile}, {@code role} and {@code user_role} in step with the caller's token. Runs on every
 * authenticated request inside the caller's {@code TenantScope}; it writes only when something changed, and every
 * change is audited with the caller as actor.
 */
@Service
public class UserProfileSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(UserProfileSynchronizer.class);

    private final UserProfileRepository profiles;
    private final UserBranchScopeRepository branchScopes;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final BranchDirectory branchDirectory;
    private final AuditTrail auditTrail;
    private final DelegationRepository delegations;

    UserProfileSynchronizer(UserProfileRepository profiles, UserBranchScopeRepository branchScopes,
            RoleRepository roles, UserRoleRepository userRoles, BranchDirectory branchDirectory,
            AuditTrail auditTrail, DelegationRepository delegations) {
        this.profiles = profiles;
        this.branchScopes = branchScopes;
        this.roles = roles;
        this.userRoles = userRoles;
        this.branchDirectory = branchDirectory;
        this.auditTrail = auditTrail;
        this.delegations = delegations;
    }

    @Transactional
    public SyncedProfile synchronize(IdentityClaims claims) {
        ActorType actorType = claims.principalType() == PrincipalType.CLIENT ? ActorType.CLIENT : ActorType.USER;
        String actorId = claims.subject().toString();

        UserProfile profile = profiles.findById(claims.subject())
                .map(existing -> refresh(existing, claims, actorType, actorId))
                .orElseGet(() -> create(claims, actorType, actorId));
        syncRoles(profile.getId(), claims.roles(), actorType, actorId);

        return new SyncedProfile(profile.getId(), profile.getStatus(), branchScopeOf(profile), profile.getClearance(),
                delegatedRoles(profile.getId(), claims.roles()));
    }

    private UserProfile create(IdentityClaims claims, ActorType actorType, String actorId) {
        profiles.findByUsername(claims.username()).ifPresent(existing -> {
            throw new IdentityConflictException("Username already belongs to another profile");
        });
        UUID homeBranchId = claims.homeBranchCode() == null ? null
                : branchDirectory.findByCodes(List.of(claims.homeBranchCode())).stream()
                        .map(BranchInfo::id).findFirst().orElse(null);
        List<BranchInfo> scopeBranches = claims.allBranches() ? List.of()
                : branchDirectory.findByCodes(claims.branchScopeCodes());
        if (!claims.allBranches() && scopeBranches.size() < claims.branchScopeCodes().size()) {
            log.warn("Ignored {} unknown branch code(s) in the token branch scope of a new profile",
                    claims.branchScopeCodes().size() - scopeBranches.size());
        }
        Classification clearance = claims.clearance() == null ? Classification.PUBLIC : claims.clearance();

        UserProfile profile = profiles.save(new UserProfile(claims.subject(), claims.username(), claims.displayName(),
                claims.email(), claims.principalType(), homeBranchId,
                claims.allBranches() ? BranchScopeType.ALL : BranchScopeType.LIST, clearance));
        scopeBranches.forEach(branch -> branchScopes.save(new UserBranchScope(profile.getId(), branch.id())));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("username", claims.username());
        details.put("principalType", claims.principalType().name());
        details.put("branchScope", claims.allBranches() ? IdentityClaims.ALL_BRANCHES
                : scopeBranches.stream().map(BranchInfo::code).sorted().toList());
        details.put("clearance", clearance.name());
        auditTrail.record(AuditEntry.success(IdentityAuditActions.PROFILE_CREATED, IdentityAuditActions.RESOURCE_USER,
                profile.getId(), details).byActor(actorType, actorId));
        return profile;
    }

    private UserProfile refresh(UserProfile profile, IdentityClaims claims, ActorType actorType, String actorId) {
        if (profile.refreshIdentity(claims.username(), claims.displayName(), claims.email())) {
            auditTrail.record(AuditEntry.success(IdentityAuditActions.PROFILE_UPDATED,
                    IdentityAuditActions.RESOURCE_USER, profile.getId(),
                    Map.of("source", "identity-provider")).byActor(actorType, actorId));
        }
        return profile;
    }

    private void syncRoles(UUID userId, Set<String> tokenRoles, ActorType actorType, String actorId) {
        Map<UUID, String> held = new HashMap<>();
        List<UserRole> current = userRoles.findByUserId(userId);
        Map<UUID, Role> rolesById = roles.findAllById(current.stream().map(UserRole::getRoleId).toList()).stream()
                .collect(Collectors.toMap(Role::getId, role -> role));
        current.forEach(userRole -> held.put(userRole.getRoleId(), rolesById.get(userRole.getRoleId()).getCode()));

        Set<String> heldCodes = new HashSet<>(held.values());
        Set<String> added = new TreeSet<>(tokenRoles);
        added.removeAll(heldCodes);
        Set<String> removed = new TreeSet<>(heldCodes);
        removed.removeAll(tokenRoles);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }

        Map<String, Role> known = roles.findByCodeIn(added).stream()
                .collect(Collectors.toMap(Role::getCode, role -> role));
        for (String code : added) {
            Role role = known.computeIfAbsent(code, missing -> roles.save(new Role(missing, missing, null)));
            userRoles.save(new UserRole(userId, role.getId()));
        }
        current.stream()
                .filter(userRole -> removed.contains(held.get(userRole.getRoleId())))
                .forEach(userRoles::delete);

        auditTrail.record(AuditEntry.success(IdentityAuditActions.ROLES_SYNCED, IdentityAuditActions.RESOURCE_USER,
                userId, Map.of("added", List.copyOf(added), "removed", List.copyOf(removed)))
                .byActor(actorType, actorId));
    }

    /**
     * Roles delegated to the user by active, approved delegations: at most what the delegator holds in their own right
     * now (their mirrored token roles; delegated roles are never passed on), minus roles the user already has.
     */
    private Set<String> delegatedRoles(UUID userId, Set<String> ownRoles) {
        Instant now = Instant.now();
        Set<String> delegated = new TreeSet<>();
        for (Delegation delegation : delegations
                .findByToUserIdAndApprovedByIsNotNullAndValidFromLessThanEqualAndValidToGreaterThan(userId, now, now)) {
            Set<String> delegatorRoles = roles.findAllById(userRoles.findByUserId(delegation.getFromUserId()).stream()
                    .map(UserRole::getRoleId).toList()).stream().map(Role::getCode).collect(Collectors.toSet());
            if (delegation.getRoleId() == null) {
                delegatorRoles.stream().filter(DelegationAdministration::isDelegable).forEach(delegated::add);
            } else {
                roles.findById(delegation.getRoleId()).map(Role::getCode).filter(delegatorRoles::contains)
                        .ifPresent(delegated::add);
            }
        }
        delegated.removeAll(ownRoles);
        return delegated;
    }

    private BranchScope branchScopeOf(UserProfile profile) {
        if (profile.getBranchScope() == BranchScopeType.ALL) {
            return BranchScope.ALL;
        }
        return BranchScope.of(branchScopes.findByUserId(profile.getId()).stream()
                .map(UserBranchScope::getBranchId).collect(Collectors.toSet()));
    }
}
