package com.jmr.jdms.migration.client;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth2 client credentials against the bank's Keycloak: the migration client authenticates as itself (role
 * {@code MIGRATION}); the token is cached until shortly before it expires. The secret is never logged.
 */
public final class ClientCredentialsTokenProvider implements TokenProvider {

    private final RestClient http;
    private final URI tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(RestClient.Builder builder, URI tokenUri, String clientId,
            String clientSecret, Clock clock) {
        this.http = builder.build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    @Override
    public synchronized String token() {
        if (token == null || Instant.now(clock).isAfter(expiresAt.minusSeconds(30))) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> response = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String accessToken)) {
                throw new IllegalStateException("The token endpoint returned no access token");
            }
            token = accessToken;
            long seconds = response.get("expires_in") instanceof Number number ? number.longValue() : 60;
            expiresAt = Instant.now(clock).plusSeconds(seconds);
        }
        return token;
    }
}
