package com.jmr.jdms.identity.infra.security;

import com.jmr.jdms.identity.domain.IdentityClaims;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Reads {@link IdentityClaims} from a validated Keycloak access token (claims defined by the
 * {@code deploy/keycloak} realm). A token without a usable subject or tenant is rejected; optional claims with bad
 * values are dropped, never widened.
 */
final class JwtIdentityClaimsReader {

    static final String TENANT = "tenant";
    static final String BRANCH_SCOPE = "branch_scope";
    static final String HOME_BRANCH = "home_branch";
    static final String CLEARANCE = "clearance";
    static final String ROLES = "roles";
    static final String CLIENT_ID = "client_id";

    private static final Pattern TENANT_CODE = Pattern.compile("[A-Z0-9_]{2,30}");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    private static final Pattern BRANCH_CODE = Pattern.compile("[A-Za-z0-9_-]{1,30}");

    private JwtIdentityClaimsReader() {
    }

    static IdentityClaims read(Jwt jwt) {
        UUID subject = parseSubject(jwt.getSubject());
        String tenant = jwt.getClaimAsString(TENANT);
        if (tenant == null || !TENANT_CODE.matcher(tenant).matches()) {
            throw invalid();
        }
        String username = Optional.ofNullable(jwt.getClaimAsString("preferred_username")).orElse(subject.toString());
        PrincipalType principalType = jwt.hasClaim(CLIENT_ID) ? PrincipalType.CLIENT : PrincipalType.USER;

        Set<String> roles = new HashSet<>();
        stringList(jwt, ROLES).stream().filter(role -> ROLE_CODE.matcher(role).matches()).forEach(roles::add);
        List<String> branchScope = stringList(jwt, BRANCH_SCOPE).stream()
                .filter(code -> IdentityClaims.ALL_BRANCHES.equals(code) || BRANCH_CODE.matcher(code).matches())
                .toList();
        String homeBranch = Optional.ofNullable(jwt.getClaimAsString(HOME_BRANCH))
                .filter(code -> BRANCH_CODE.matcher(code).matches()).orElse(null);

        return new IdentityClaims(subject, tenant, username, jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"), principalType, roles, branchScope, homeBranch,
                clearance(jwt.getClaimAsString(CLEARANCE)));
    }

    private static UUID parseSubject(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    private static List<String> stringList(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof String single) {
            return List.of(single);
        }
        List<String> list = jwt.hasClaim(claim) ? jwt.getClaimAsStringList(claim) : null;
        return list == null ? List.of() : list;
    }

    private static Classification clearance(String value) {
        return Arrays.stream(Classification.values()).filter(level -> level.name().equals(value)).findFirst()
                .orElse(null);
    }

    private static InvalidBearerTokenException invalid() {
        // Deliberately generic: the caller learns only that the token is not acceptable
        return new InvalidBearerTokenException("The access token is not valid for this service.");
    }
}
