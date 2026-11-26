package com.jmr.jdms.identity.infra.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param issuerUri OIDC issuer of access tokens (Keycloak realm URL); tokens must carry exactly this {@code iss}
 * @param audience  audience every access token must contain ({@code aud}); rejects ID tokens and tokens for other
 *                  APIs
 */
@ConfigurationProperties("jdms.security")
@Validated
public record JdmsSecurityProperties(@NotBlank String issuerUri, @NotBlank String audience) {
}
