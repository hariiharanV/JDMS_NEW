package com.jmr.jdms.identity.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The roles a user held at their last sign-in, from the mirror of the token's realm roles ({@code user_role}, ADR-0007).
 * For administrators' screens only: authorization always uses the roles in the current token.
 */
@Component
class UserRoles {

    private final UserRoleRepository userRoles;
    private final RoleRepository roles;

    UserRoles(UserRoleRepository userRoles, RoleRepository roles) {
        this.userRoles = userRoles;
        this.roles = roles;
    }

    /** Role codes, sorted. */
    List<String> of(UUID userId) {
        List<UUID> roleIds = userRoles.findByUserId(userId).stream().map(UserRole::getRoleId).toList();
        Map<UUID, String> codes = roles.findAllById(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode, (a, b) -> a));
        return roleIds.stream().map(codes::get).filter(Objects::nonNull).distinct().sorted().toList();
    }
}
