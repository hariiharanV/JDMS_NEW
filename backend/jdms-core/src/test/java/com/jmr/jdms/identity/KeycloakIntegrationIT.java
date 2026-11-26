package com.jmr.jdms.identity;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.TestcontainersConfiguration;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end with a real Keycloak running the {@code deploy/keycloak} realm and the production token decoder
 * (issuer discovery, JWKS, issuer and audience checks): proves the realm's clients, mappers and claims work with
 * the backend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestTenants.class})
class KeycloakIntegrationIT {

    private static final String DEV_PASSWORD = randomSecret();
    private static final String CLIENT_SECRET = randomSecret();
    private static final String REDIRECT_URI = "http://localhost:5180/callback";

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(System.getProperty("test.image.keycloak"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("JDMS_DEV_USER_PASSWORD", DEV_PASSWORD)
            .withEnv("JDMS_INTEGRATION_TEST_CLIENT_SECRET", CLIENT_SECRET)
            .withEnv("JDMS_MIGRATION_CLIENT_SECRET", randomSecret())
            .withEnv("JDMS_WEB_PUBLIC_URL", "http://localhost:5180")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../../deploy/keycloak/jdms-local-realm.json").toAbsolutePath()),
                    "/opt/keycloak/data/import/jdms-local-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/jdms-local/.well-known/openid-configuration").forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("jdms.security.issuer-uri", KeycloakIntegrationIT::issuer);
    }

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TenantDirectory tenantDirectory;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void seedTenantOfTheDevUsers() {
        if (tenantDirectory.findActiveByCode("BANK1").isEmpty()) {
            testTenants.createWithCode("BANK1", "001", "002", "003");
        }
    }

    @Test
    void clientCredentialsTokenAuthenticatesTheIntegrationClient() throws Exception {
        String token = tokenRequest(Map.of("grant_type", "client_credentials", "client_id", "jdms-integration-test",
                "client_secret", CLIENT_SECRET)).get("access_token").asString();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("BANK1"))
                .andExpect(jsonPath("$.principalType").value("CLIENT"))
                .andExpect(jsonPath("$.username").value("service-account-jdms-integration-test"))
                .andExpect(jsonPath("$.roles").value(contains("INTEGRATION_CLIENT")))
                .andExpect(jsonPath("$.allBranches").value(true))
                .andExpect(jsonPath("$.clearance").value("CONFIDENTIAL"));
    }

    @Test
    void userLoginWithPkceAuthenticatesTheUser() throws Exception {
        JsonNode tokens = loginWithPkce("checker1");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.get("access_token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("BANK1"))
                .andExpect(jsonPath("$.principalType").value("USER"))
                .andExpect(jsonPath("$.username").value("checker1"))
                .andExpect(jsonPath("$.displayName").value("Chris Checker"))
                .andExpect(jsonPath("$.roles").value(contains("CHECKER")))
                .andExpect(jsonPath("$.branchCodes").value(contains("001", "002")))
                .andExpect(jsonPath("$.clearance").value("CONFIDENTIAL"));
    }

    @Test
    void idTokenIsNotAnAccessToken() throws Exception {
        JsonNode tokens = loginWithPkce("viewer1");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.get("id_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userOfAnUnknownTenantIsRejected() throws Exception {
        // bank2user1 belongs to BANK2, which does not exist in this database
        JsonNode tokens = loginWithPkce("bank2user1");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.get("access_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    /** Authorization code + PKCE through Keycloak's login form, as the jdms-web browser client does. */
    private JsonNode loginWithPkce(String username) throws Exception {
        String verifier = randomSecret() + randomSecret();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = issuer() + "/protocol/openid-connect/auth?" + form(Map.of("client_id", "jdms-web",
                "response_type", "code", "scope", "openid", "redirect_uri", REDIRECT_URI, "state", "s1",
                "code_challenge", challenge, "code_challenge_method", "S256"));

        HttpResponse<String> loginPage = http.send(HttpRequest.newBuilder(URI.create(authorize)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher action = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"|"
                + "<form[^>]*action=\"([^\"]+)\"[^>]*id=\"kc-form-login\"").matcher(loginPage.body());
        if (!action.find()) {
            throw new AssertionError("Keycloak login form not found (HTTP " + loginPage.statusCode() + ")");
        }
        String postUrl = (action.group(1) != null ? action.group(1) : action.group(2)).replace("&amp;", "&");

        // Java's CookieManager drops cookies for the dotless host "localhost"; pass Keycloak's session cookies on
        String cookies = loginPage.headers().allValues("Set-Cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0]).collect(Collectors.joining("; "));
        HttpResponse<String> afterLogin = http.send(HttpRequest.newBuilder(URI.create(postUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookies)
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", username, "password",
                        DEV_PASSWORD, "credentialId", "")))).build(), HttpResponse.BodyHandlers.ofString());
        String location = afterLogin.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError("No redirect after login (HTTP " + afterLogin.statusCode() + ")"));
        Matcher code = Pattern.compile("[?&]code=([^&]+)").matcher(location);
        if (!location.startsWith(REDIRECT_URI) || !code.find()) {
            throw new AssertionError("Unexpected redirect after login");
        }

        return tokenRequest(Map.of("grant_type", "authorization_code", "client_id", "jdms-web", "redirect_uri",
                REDIRECT_URI, "code", URLDecoder.decode(code.group(1), StandardCharsets.UTF_8),
                "code_verifier", verifier));
    }

    private JsonNode tokenRequest(Map<String, String> parameters) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError("Token request failed: HTTP " + response.statusCode());
        }
        return JsonMapper.shared().readTree(response.body());
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/jdms-local";
    }

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
