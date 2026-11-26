package com.jmr.jdms.identity.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.identity.domain.IdentityClaims;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class JwtIdentityClaimsReaderTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    @Test
    void readsKeycloakClaims() {
        IdentityClaims claims = JwtIdentityClaimsReader.read(jwt(builder -> builder
                .claim("preferred_username", "checker1").claim("name", "Chris Checker")
                .claim("email", "checker1@example.org").claim("roles", List.of("CHECKER", "VIEWER"))
                .claim("branch_scope", List.of("001", "002")).claim("home_branch", "001")
                .claim("clearance", "CONFIDENTIAL")));

        assertThat(claims.subject()).isEqualTo(SUBJECT);
        assertThat(claims.tenantCode()).isEqualTo("BANK1");
        assertThat(claims.username()).isEqualTo("checker1");
        assertThat(claims.displayName()).isEqualTo("Chris Checker");
        assertThat(claims.principalType()).isEqualTo(PrincipalType.USER);
        assertThat(claims.roles()).containsExactlyInAnyOrder("CHECKER", "VIEWER");
        assertThat(claims.branchScopeCodes()).containsExactly("001", "002");
        assertThat(claims.allBranches()).isFalse();
        assertThat(claims.homeBranchCode()).isEqualTo("001");
        assertThat(claims.clearance()).isEqualTo(Classification.CONFIDENTIAL);
    }

    @Test
    void clientIdClaimMakesAClient() {
        assertThat(JwtIdentityClaimsReader.read(jwt(builder -> builder.claim("client_id", "flexcube")))
                .principalType()).isEqualTo(PrincipalType.CLIENT);
    }

    @Test
    void badOptionalValuesAreDroppedNeverWidened() {
        IdentityClaims claims = JwtIdentityClaimsReader.read(jwt(builder -> builder
                .claim("roles", List.of("CHECKER", "lowercase", "WITH SPACE", "<script>"))
                .claim("branch_scope", List.of("001", "../etc", "x".repeat(31)))
                .claim("home_branch", "a b")
                .claim("clearance", "TOP_SECRET")));

        assertThat(claims.roles()).containsExactly("CHECKER");
        assertThat(claims.branchScopeCodes()).containsExactly("001");
        assertThat(claims.homeBranchCode()).isNull();
        assertThat(claims.clearance()).isNull();
    }

    @Test
    void singleStringBranchScopeIsAccepted() {
        assertThat(JwtIdentityClaimsReader.read(jwt(builder -> builder.claim("branch_scope", "ALL"))).allBranches())
                .isTrue();
    }

    @Test
    void missingOrMalformedTenantOrSubjectIsRejected() {
        assertThatThrownBy(() -> JwtIdentityClaimsReader.read(jwt(builder -> builder.claims(c -> c.remove("tenant")))))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> JwtIdentityClaimsReader.read(jwt(builder -> builder.claim("tenant", "bank1"))))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> JwtIdentityClaimsReader.read(jwt(builder -> builder.subject("not-a-uuid"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(Consumer<Jwt.Builder> customizer) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(SUBJECT.toString())
                .claim("tenant", "BANK1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        customizer.accept(builder);
        return builder.build();
    }
}
