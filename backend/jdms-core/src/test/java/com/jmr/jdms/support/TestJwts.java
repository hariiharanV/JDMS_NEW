package com.jmr.jdms.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs access tokens shaped like the {@code deploy/keycloak} realm's, with a key generated per test run. Tokens
 * go through the application's real validation (signature, expiry, issuer, audience) and converter.
 */
public final class TestJwts {

    public static final String ISSUER = "https://issuer.test/realms/jdms-test";
    public static final String AUDIENCE = "jdms-api";

    private static final RSAKey KEY = generate();
    private static final RSAKey OTHER_KEY = generate();

    private TestJwts() {
    }

    public static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Builder user(String tenantCode) {
        return new Builder().claim("tenant", tenantCode);
    }

    /** A service-account token as issued by client credentials ({@code client_id} claim present). */
    public static Builder client(String tenantCode, String clientId) {
        return new Builder().claim("tenant", tenantCode).claim("client_id", clientId)
                .claim("preferred_username", "service-account-" + clientId);
    }

    public static final class Builder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private RSAKey signingKey = KEY;

        private Builder() {
            UUID subject = UUID.randomUUID();
            claims.put("iss", ISSUER);
            claims.put("aud", List.of(AUDIENCE));
            claims.put("sub", subject.toString());
            claims.put("preferred_username", "user-" + subject.toString().substring(0, 8));
            claims.put("name", "Test User");
            claims.put("roles", List.of());
            claims.put("branch_scope", List.of());
        }

        public Builder subject(UUID subject) {
            return claim("sub", subject.toString());
        }

        public Builder username(String username) {
            return claim("preferred_username", username);
        }

        public Builder roles(String... roles) {
            return claim("roles", List.of(roles));
        }

        public Builder branchScope(String... codes) {
            return claim("branch_scope", List.of(codes));
        }

        public Builder homeBranch(String code) {
            return claim("home_branch", code);
        }

        public Builder clearance(String clearance) {
            return claim("clearance", clearance);
        }

        public Builder claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        public Builder without(String name) {
            claims.remove(name);
            return this;
        }

        public Builder expiresAt(Instant instant) {
            this.expiresAt = instant;
            return this;
        }

        public Builder signedWithUnknownKey() {
            this.signingKey = OTHER_KEY;
            return this;
        }

        public String subject() {
            return (String) claims.get("sub");
        }

        public String sign() {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder()
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))))
                    .expirationTime(Date.from(expiresAt));
            claims.forEach(set::claim);
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                        .build(), set.build());
                jwt.sign(new RSASSASigner(signingKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        public String bearer() {
            return "Bearer " + sign();
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
