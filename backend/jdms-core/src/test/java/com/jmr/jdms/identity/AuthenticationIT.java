package com.jmr.jdms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.identity.domain.Role;
import com.jmr.jdms.identity.domain.RoleRepository;
import com.jmr.jdms.identity.domain.UserProfileRepository;
import com.jmr.jdms.identity.domain.UserRoleRepository;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.shared.web.RequestIdFilter;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.LogCapture;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** B-01: every request gets a RequestContext from a valid token for a known, active tenant; anything else is 401. */
@IntegrationTest
class AuthenticationIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;
    @Autowired AuditEventRepository auditEvents;
    @Autowired UserProfileRepository profiles;
    @Autowired UserRoleRepository userRoles;
    @Autowired RoleRepository roles;

    CreatedTenant tenant;

    @BeforeEach
    void createTenant() {
        tenant = testTenants.create("001", "002");
    }

    @Test
    void requestWithoutTokenIsRejectedWithProblemJson() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader("X-Request-Id"));
    }

    static Stream<Arguments> unacceptableTokens() {
        return Stream.of(
                Arguments.of("signed by an unknown key", (TokenFactory) code -> TestJwts.user(code).signedWithUnknownKey()),
                Arguments.of("expired", (TokenFactory) code -> TestJwts.user(code).expiresAt(Instant.now().minusSeconds(120))),
                Arguments.of("wrong issuer", (TokenFactory) code -> TestJwts.user(code).claim("iss", "https://evil.example/realms/x")),
                Arguments.of("wrong audience (e.g. an ID token)", (TokenFactory) code -> TestJwts.user(code).claim("aud", List.of("jdms-web"))),
                Arguments.of("no tenant claim", (TokenFactory) code -> TestJwts.user(code).without("tenant")),
                Arguments.of("unknown tenant", (TokenFactory) code -> TestJwts.user("NOSUCHTENANT")),
                Arguments.of("malformed tenant", (TokenFactory) code -> TestJwts.user(code + "' OR '1'='1")),
                Arguments.of("subject is not a UUID", (TokenFactory) code -> TestJwts.user(code).claim("sub", "admin")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unacceptableTokens")
    void unacceptableTokensAreRejected(String description, TokenFactory token) throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", token.create(tenant.code()).bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void suspendedTenantIsRejected() throws Exception {
        CreatedTenant suspended = testTenants.create("001");
        testTenants.suspend(suspended.id());

        mvc.perform(get("/api/v1/me").header("Authorization", TestJwts.user(suspended.code()).bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenGivesTheRequestContext() throws Exception {
        TestJwts.Builder token = TestJwts.user(tenant.code()).username("maker-a").roles("MAKER")
                .branchScope("001").homeBranch("001").clearance("CONFIDENTIAL");

        MvcResult result = me(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value(tenant.code()))
                .andExpect(jsonPath("$.userId").value(token.subject()))
                .andExpect(jsonPath("$.username").value("maker-a"))
                .andExpect(jsonPath("$.principalType").value("USER"))
                .andExpect(jsonPath("$.roles").value(contains("MAKER")))
                .andExpect(jsonPath("$.allBranches").value(false))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")))
                .andExpect(jsonPath("$.clearance").value("CONFIDENTIAL"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"requestId\":\"" + result.getResponse().getHeader("X-Request-Id") + "\"");
    }

    @Test
    void firstLoginCreatesTheProfileOnceAndAuditsIt() throws Exception {
        TestJwts.Builder token = TestJwts.user(tenant.code()).roles("VIEWER").branchScope("002");

        me(token).andExpect(status().isOk());
        me(token).andExpect(status().isOk());

        UUID subject = UUID.fromString(token.subject());
        assertThat(inTenant(() -> profiles.findById(subject))).isPresent();
        List<AuditEvent> created = inTenant(() -> auditEvents
                .findByResourceTypeAndResourceIdOrderByOccurredAt("USER_PROFILE", subject.toString())).stream()
                .filter(event -> event.getAction().equals("USER_PROFILE_CREATED")).toList();
        assertThat(created).singleElement().satisfies(event -> {
            assertThat(event.getActorType()).isEqualTo(ActorType.USER);
            assertThat(event.getActorId()).isEqualTo(subject.toString());
            assertThat(event.getRequestId()).isNotBlank();
            assertThat(event.getClientIp()).isNotBlank();
            assertThat(event.getDetails()).contains("\"branchScope\"").contains("002");
        });
    }

    @Test
    void tokenSeedsBranchScopeAndClearanceButTheProfileIsAuthoritative() throws Exception {
        UUID subject = UUID.randomUUID();
        me(TestJwts.user(tenant.code()).subject(subject).branchScope("001").clearance("INTERNAL"))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")));

        me(TestJwts.user(tenant.code()).subject(subject).branchScope("ALL").clearance("RESTRICTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allBranches").value(false))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")))
                .andExpect(jsonPath("$.clearance").value("INTERNAL"));
    }

    @Test
    void rolesAreMirroredFromTheTokenAndChangesAudited() throws Exception {
        UUID subject = UUID.randomUUID();
        me(TestJwts.user(tenant.code()).subject(subject).roles("MAKER", "VIEWER")).andExpect(status().isOk());
        assertThat(heldRoles(subject)).containsExactlyInAnyOrder("MAKER", "VIEWER");

        me(TestJwts.user(tenant.code()).subject(subject).roles("VIEWER", "CHECKER"))
                .andExpect(jsonPath("$.roles").value(contains("CHECKER", "VIEWER")));

        assertThat(heldRoles(subject)).containsExactlyInAnyOrder("VIEWER", "CHECKER");
        List<AuditEvent> synced = inTenant(() -> auditEvents
                .findByResourceTypeAndResourceIdOrderByOccurredAt("USER_PROFILE", subject.toString())).stream()
                .filter(event -> event.getAction().equals("USER_ROLES_SYNCED")).toList();
        assertThat(synced).hasSize(2);
        JsonNode details = JsonMapper.shared().readTree(synced.getLast().getDetails());
        assertThat(details.get("added").toString()).isEqualTo("[\"CHECKER\"]");
        assertThat(details.get("removed").toString()).isEqualTo("[\"MAKER\"]");
    }

    @Test
    void recreatedIdentityProviderUserDoesNotInheritTheOldProfile() throws Exception {
        me(TestJwts.user(tenant.code()).username("recycled").roles("SECURITY_ADMIN").branchScope("ALL")
                .clearance("RESTRICTED")).andExpect(status().isOk());

        // same username, new subject: refused cleanly, the old profile's access is not handed over
        me(TestJwts.user(tenant.code()).username("recycled").roles("VIEWER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void unknownBranchCodesInTheTokenGrantNothing() throws Exception {
        me(TestJwts.user(tenant.code()).branchScope("001", "999"))
                .andExpect(jsonPath("$.allBranches").value(false))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")));
    }

    @Test
    void allBranchesScopeAndMissingClearance() throws Exception {
        me(TestJwts.user(tenant.code()).branchScope("ALL"))
                .andExpect(jsonPath("$.allBranches").value(true))
                .andExpect(jsonPath("$.clearance").value("PUBLIC"));
    }

    @Test
    void clientCredentialsTokenIsAClient() throws Exception {
        TestJwts.Builder token = TestJwts.client(tenant.code(), "flexcube").roles("INTEGRATION_CLIENT");

        me(token).andExpect(jsonPath("$.principalType").value("CLIENT"))
                .andExpect(jsonPath("$.username").value("service-account-flexcube"));

        AuditEvent created = inTenant(() -> auditEvents
                .findByResourceTypeAndResourceIdOrderByOccurredAt("USER_PROFILE", token.subject())).getFirst();
        assertThat(created.getActorType()).isEqualTo(ActorType.CLIENT);
    }

    @Test
    void logLinesCarryRequestTenantAndUser() throws Exception {
        TestJwts.Builder token = TestJwts.user(tenant.code());
        List<String> lines;
        try (LogCapture logs = LogCapture.of(RequestIdFilter.class, Level.DEBUG)) {
            mvc.perform(get("/api/v1/me").header("X-Request-Id", "auth-log-check-01")
                    .header("Authorization", token.bearer())).andExpect(status().isOk());
            lines = logs.lines();
        }

        assertThat(lines.stream().filter(line -> line.contains("\"requestId\":\"auth-log-check-01\"")))
                .anySatisfy(line -> assertThat(line)
                        .contains("\"tenantId\":\"" + tenant.id() + "\"")
                        .contains("\"userId\":\"" + token.subject() + "\""))
                .allSatisfy(line -> assertThat(line).doesNotContain("eyJ")); // never a token
    }

    private ResultActions me(TestJwts.Builder token) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", token.bearer()));
    }

    private List<String> heldRoles(UUID userId) {
        return inTenant(() -> roles.findAllById(userRoles.findByUserId(userId).stream()
                .map(userRole -> userRole.getRoleId()).toList()).stream().map(Role::getCode).toList());
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }

    @FunctionalInterface
    interface TokenFactory {
        TestJwts.Builder create(String tenantCode);
    }
}
