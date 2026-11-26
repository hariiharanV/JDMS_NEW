package com.jmr.jdms.shared.context;

import com.jmr.jdms.shared.Classification;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is calling and in which tenant, for the current authenticated request. Built once per request from the
 * validated access token and the caller's {@code user_profile}; it is the principal of the request's
 * {@link Authentication}.
 *
 * @param tenantId    tenant from the token, verified to exist and be active
 * @param tenantCode  the tenant's code (the token's {@code tenant} claim)
 * @param subjectId   Keycloak subject; also the {@code user_profile} ID
 * @param username    Keycloak username (service accounts: {@code service-account-<client>})
 * @param principalType person or bank system
 * @param roles       effective roles: realm roles from the token plus roles delegated to the caller
 * @param delegatedRoles the roles held only through an active delegation (never re-delegable)
 * @param branchScope branch scope from {@code user_profile}
 * @param clearance   clearance from {@code user_profile}
 * @param requestId   the request's {@code X-Request-Id}
 */
public record RequestContext(
        UUID tenantId,
        String tenantCode,
        UUID subjectId,
        String username,
        PrincipalType principalType,
        Set<String> roles,
        Set<String> delegatedRoles,
        BranchScope branchScope,
        Classification clearance,
        String requestId) {

    public RequestContext {
        roles = Set.copyOf(roles);
        delegatedRoles = delegatedRoles == null ? Set.of() : Set.copyOf(delegatedRoles);
    }

    /** Roles the caller holds in their own right (excluding delegated ones). */
    public Set<String> ownRoles() {
        return roles.stream().filter(role -> !delegatedRoles.contains(role))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** The current request's context, if the request is authenticated. */
    public static Optional<RequestContext> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof RequestContext context) {
            return Optional.of(context);
        }
        return Optional.empty();
    }

    /** The current request's context; fails when called outside an authenticated request. */
    public static RequestContext require() {
        return current().orElseThrow(() -> new IllegalStateException("No authenticated request context"));
    }
}
