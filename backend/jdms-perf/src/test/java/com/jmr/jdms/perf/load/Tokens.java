package com.jmr.jdms.perf.load;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access tokens of the dev users, as the web client gets them: authorization code with PKCE through the Keycloak
 * login form (no extra client or grant in the realm), refreshed before they expire. Tokens are never logged.
 */
final class Tokens {

    private record Token(String access, String refresh, Instant expires) {
    }

    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\""
            + "|<form[^>]*action=\"([^\"]+)\"[^>]*id=\"kc-form-login\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Tokens() {
    }

    /** A valid access token of the user (logs in or refreshes when needed). */
    static String of(String username) {
        return TOKENS.compute(username, (user, token) -> {
            if (token != null && token.expires().isAfter(Instant.now().plusSeconds(120))) {
                return token;
            }
            try {
                return token == null ? login(user) : refresh(token, user);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("Could not get a token for " + user, e);
            }
        }).access();
    }

    private static Token login(String username) throws IOException, InterruptedException {
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String verifier = random(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        String authorize = LoadSettings.KEYCLOAK + "/protocol/openid-connect/auth?" + form(Map.of(
                "client_id", LoadSettings.WEB_CLIENT, "response_type", "code", "scope", "openid",
                "redirect_uri", LoadSettings.REDIRECT_URI, "code_challenge", challenge,
                "code_challenge_method", "S256", "state", random(12)));
        HttpResponse<String> form = browser.send(HttpRequest.newBuilder(URI.create(authorize)).build(),
                HttpResponse.BodyHandlers.ofString());
        // the login cookies are "Secure"; java.net.CookieManager would not send them back over http://localhost
        String cookies = form.headers().allValues("Set-Cookie").stream().map(cookie -> cookie.split(";", 2)[0])
                .collect(Collectors.joining("; "));
        Matcher action = FORM_ACTION.matcher(form.body());
        if (!action.find()) {
            throw new IllegalStateException("No Keycloak login form for " + username);
        }
        String loginUrl = (action.group(1) != null ? action.group(1) : action.group(2)).replace("&amp;", "&");
        HttpResponse<String> loggedIn = browser.send(HttpRequest.newBuilder(URI.create(loginUrl))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookies)
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", username,
                        "password", LoadSettings.password())))).build(), HttpResponse.BodyHandlers.ofString());
        String location = loggedIn.headers().firstValue("Location").orElseThrow(() ->
                new IllegalStateException("Login of " + username + " failed (HTTP " + loggedIn.statusCode() + ")"));
        Matcher code = Pattern.compile("[?&]code=([^&]+)").matcher(location);
        if (!code.find()) {
            throw new IllegalStateException("No authorization code for " + username);
        }
        return tokenRequest(browser, Map.of("grant_type", "authorization_code", "client_id", LoadSettings.WEB_CLIENT,
                "redirect_uri", LoadSettings.REDIRECT_URI, "code", code.group(1), "code_verifier", verifier));
    }

    private static Token refresh(Token token, String username) throws IOException, InterruptedException {
        try {
            return tokenRequest(HttpClient.newHttpClient(), Map.of("grant_type", "refresh_token",
                    "client_id", LoadSettings.WEB_CLIENT, "refresh_token", token.refresh()));
        } catch (IllegalStateException e) {
            return login(username); // the session ended: log in again
        }
    }

    private static Token tokenRequest(HttpClient client, Map<String, String> parameters)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(LoadSettings.KEYCLOAK
                + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token request refused (HTTP " + response.statusCode() + ")");
        }
        JsonNode body = JSON.readTree(response.body());
        return new Token(body.path("access_token").asString(), body.path("refresh_token").asString(),
                Instant.now().plusSeconds(body.path("expires_in").asLong(300)));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
