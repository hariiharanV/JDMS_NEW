package com.jmr.jdms.support;

import com.jmr.jdms.identity.infra.security.SecurityConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Replaces only the key source of token validation: the test key instead of the Keycloak JWKS endpoint. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestJwts.publicKey()).build();
        decoder.setJwtValidator(SecurityConfiguration.tokenValidator(TestJwts.ISSUER, TestJwts.AUDIENCE));
        return decoder;
    }
}
