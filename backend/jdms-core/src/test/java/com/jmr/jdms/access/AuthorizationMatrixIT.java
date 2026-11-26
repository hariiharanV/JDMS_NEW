package com.jmr.jdms.access;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.DocumentGrants;
import com.jmr.jdms.access.api.GrantSource;
import com.jmr.jdms.access.api.ScopeType;
import com.jmr.jdms.access.domain.AccessRule;
import com.jmr.jdms.access.domain.AccessRuleRepository;
import com.jmr.jdms.access.domain.BranchConstraint;
import com.jmr.jdms.access.domain.Effect;
import com.jmr.jdms.access.domain.PrincipalKind;
import com.jmr.jdms.access.domain.RuleSpec;
import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.contracts.AccessRulesChanged;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-04 authorization matrix through the API with the baseline rules: actors × actions × {own branch, other branch,
 * above clearance}, plus other tenant, legal hold, explicit deny and grants. Allowed: 2xx. Denied on a document the
 * actor may see: 403; on one they may not see: 404 (existence not revealed). Admin endpoints: 403.
 */
@IntegrationTest
class AuthorizationMatrixIT {

    /** The PoC actor matrix (spec §3, baseline rules), written out independently of the code under test. */
    static final Map<String, Set<Action>> MATRIX = Map.of(
            "MAKER", Set.of(Action.VIEW_METADATA, Action.DOWNLOAD, Action.EDIT_METADATA, Action.CHECK_OUT,
                    Action.UPLOAD),
            "CHECKER", Set.of(Action.VIEW_METADATA, Action.DOWNLOAD),
            "VIEWER", Set.of(Action.VIEW_METADATA),
            "RECORDS_OFFICER", Set.of(Action.VIEW_METADATA, Action.DOWNLOAD),
            "INTEGRATION_CLIENT", Set.of(Action.VIEW_METADATA, Action.DOWNLOAD, Action.EDIT_METADATA,
                    Action.CHECK_OUT, Action.UPLOAD),
            "TENANT_ADMIN", Set.of(Action.ADMIN_METADATA),
            "SECURITY_ADMIN", Set.of(Action.ADMIN_ACCESS));
    /** Roles limited to their own branches. */
    static final Set<String> BRANCH_BOUND = Set.of("MAKER", "CHECKER", "VIEWER");
    static final List<Action> TESTED = List.of(Action.VIEW_METADATA, Action.DOWNLOAD, Action.EDIT_METADATA,
            Action.CHECK_OUT, Action.UPLOAD, Action.ADMIN_METADATA, Action.ADMIN_ACCESS);
    static final Set<Action> DOCUMENT_ACTIONS = Set.of(Action.VIEW_METADATA, Action.DOWNLOAD, Action.EDIT_METADATA,
            Action.CHECK_OUT);

    enum Situation { OWN_BRANCH, OTHER_BRANCH, ABOVE_CLEARANCE }

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessRuleRepository rules;
    @Autowired AuditEventRepository auditEvents;
    @Autowired DocumentGrants grants;
    @Autowired ApplicationEventPublisher events;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder owner;
    String documentId;
    String versionId;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        owner = TestJwts.user(tenant.code()).username("owner").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        var body = json(api.upload(owner, "{\"documentTypeCode\":\"RECEIPT\",\"title\":\"R\",\"branchCode\":\"001\","
                + "\"classification\":\"CONFIDENTIAL\",\"cif\":\"C1\",\"customFields\":{\"amount\":1}}",
                randomBytes(500), "r.pdf", key()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        documentId = body.get("id").asString();
        versionId = body.at("/currentVersion/id").asString();
    }

    @TestFactory
    Stream<DynamicTest> actorsActionsAndSituations() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String role : MATRIX.keySet().stream().sorted().toList()) {
            for (Situation situation : Situation.values()) {
                for (Action action : TESTED) {
                    tests.add(DynamicTest.dynamicTest(role + " / " + situation + " / " + action,
                            () -> assertOutcome(role, situation, action)));
                }
            }
        }
        return tests.stream();
    }

    private void assertOutcome(String role, Situation situation, Action action) throws Exception {
        TestJwts.Builder actor = actor(role, situation);
        boolean roleAllows = MATRIX.get(role).contains(action);
        boolean documentAction = DOCUMENT_ACTIONS.contains(action);
        boolean branchOk = situation != Situation.OTHER_BRANCH || !BRANCH_BOUND.contains(role);
        boolean clearanceOk = situation != Situation.ABOVE_CLEARANCE;
        boolean allowed = roleAllows && (!documentAction || (branchOk && clearanceOk))
                && (action != Action.UPLOAD || branchOk);
        boolean canSee = MATRIX.get(role).contains(Action.VIEW_METADATA) && branchOk && clearanceOk;

        int status = perform(actor, action);
        int expected = allowed ? expectedSuccess(action) : documentAction ? (canSee ? 403 : 404) : 403;
        assertThat(status).as("%s / %s / %s", role, situation, action).isEqualTo(expected);
    }

    @Test
    void anotherTenantSeesNothing() throws Exception {
        TestJwts.Builder outsider = TestJwts.user(testTenants.create("001").code()).roles("MAKER", "RECORDS_OFFICER")
                .branchScope("ALL").clearance("RESTRICTED");
        for (Action action : DOCUMENT_ACTIONS) {
            assertThat(perform(outsider, action)).as(action.name()).isEqualTo(404);
        }
    }

    @Test
    void legalHoldBlocksChangesNotReading() throws Exception {
        jdbc.update("update document set on_legal_hold = true where id = ?::uuid", documentId);

        assertThat(perform(owner, Action.VIEW_METADATA)).isEqualTo(200);
        assertThat(perform(owner, Action.DOWNLOAD)).isEqualTo(200);
        assertThat(perform(owner, Action.EDIT_METADATA)).isEqualTo(403);
        assertThat(perform(owner, Action.CHECK_OUT)).isEqualTo(403);
    }

    @Test
    void explicitDenyHidesTheDocumentFromThatUserAndDenialsAreAudited() throws Exception {
        TestJwts.Builder colleague = TestJwts.user(tenant.code()).username("colleague").roles("MAKER")
                .branchScope("001").clearance("CONFIDENTIAL");
        assertThat(perform(colleague, Action.VIEW_METADATA)).isEqualTo(200);
        UUID ruleId = addRule(new RuleSpec(ScopeType.DOCUMENT, UUID.fromString(documentId), PrincipalKind.USER,
                colleague.subject(), Set.of(Action.VIEW_METADATA), Effect.DENY, BranchConstraint.ALL, Set.of(), null,
                null, "test deny"));

        assertThat(perform(colleague, Action.VIEW_METADATA)).isEqualTo(404);
        assertThat(perform(colleague, Action.DOWNLOAD)).isEqualTo(404);
        assertThat(perform(owner, Action.VIEW_METADATA)).isEqualTo(200);
        api.as(colleague, get("/api/v1/documents")).andExpect(org.springframework.test.web.servlet.result
                .MockMvcResultMatchers.jsonPath("$.total").value(0));

        AuditEvent denial = inTenant(() -> auditEvents.findByActionOrderByOccurredAt("ACCESS_DENIED")).stream()
                .filter(event -> event.getActorId().equals(colleague.subject())).findFirst().orElseThrow();
        assertThat(denial.getResourceId()).isEqualTo(documentId);
        assertThat(denial.getDetails()).contains("DENIED_EXPLICIT_RULE").contains(ruleId.toString());
    }

    @Test
    void grantsOpenOneDocumentUntilTheyExpire() throws Exception {
        TestJwts.Builder requester = TestJwts.user(tenant.code()).username("requester").roles("VIEWER")
                .branchScope("002").clearance("CONFIDENTIAL");
        api.as(requester, get("/api/v1/me"));
        assertThat(perform(requester, Action.VIEW_METADATA)).isEqualTo(404);

        UUID grant = inTenant(() -> grants.grant(UUID.fromString(documentId), UUID.fromString(requester.subject()),
                Set.of(Action.VIEW_METADATA, Action.DOWNLOAD), Instant.now().plus(1, ChronoUnit.DAYS),
                GrantSource.RETRIEVAL_REQUEST, null));
        assertThat(perform(requester, Action.VIEW_METADATA)).isEqualTo(200);
        assertThat(perform(requester, Action.DOWNLOAD)).isEqualTo(200);
        assertThat(perform(requester, Action.EDIT_METADATA)).isEqualTo(403);
        api.as(requester, get("/api/v1/documents")).andExpect(org.springframework.test.web.servlet.result
                .MockMvcResultMatchers.jsonPath("$.total").value(1));

        jdbc.update("update document_grant set valid_until = now() - interval '1 second' where id = ?", grant);
        assertThat(perform(requester, Action.VIEW_METADATA)).isEqualTo(404);

        UUID second = inTenant(() -> grants.grant(UUID.fromString(documentId), UUID.fromString(requester.subject()),
                Set.of(Action.VIEW_METADATA), Instant.now().plus(1, ChronoUnit.DAYS), GrantSource.SHARE, null));
        inTenant(() -> {
            grants.revoke(second);
            return null;
        });
        assertThat(perform(requester, Action.VIEW_METADATA)).isEqualTo(404);
    }

    /** One identity per role and situation (a username always maps to the same subject). */
    private final Map<String, TestJwts.Builder> actors = new java.util.HashMap<>();

    private TestJwts.Builder actor(String role, Situation situation) {
        return actors.computeIfAbsent(role + "/" + situation, ignored -> newActor(role, situation));
    }

    private TestJwts.Builder newActor(String role, Situation situation) {
        TestJwts.Builder builder = role.equals("INTEGRATION_CLIENT")
                ? TestJwts.client(tenant.code(), "client-" + situation.name().toLowerCase())
                : TestJwts.user(tenant.code()).username(role.toLowerCase() + "-" + situation.name().toLowerCase());
        return builder.roles(role)
                .branchScope(situation == Situation.OTHER_BRANCH ? "002" : "001")
                .clearance(situation == Situation.ABOVE_CLEARANCE ? "INTERNAL" : "CONFIDENTIAL");
    }

    private int perform(TestJwts.Builder actor, Action action) throws Exception {
        String document = "/api/v1/documents/" + documentId;
        return switch (action) {
            case VIEW_METADATA -> code(api.as(actor, get(document)));
            case DOWNLOAD -> code(api.as(actor, get(document + "/versions/" + versionId + "/content")));
            case EDIT_METADATA -> code(api.as(actor, patch(document).header("If-Match", currentETag())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Edited " + UUID.randomUUID()
                            + "\"}")));
            case CHECK_OUT -> {
                int result = code(api.as(actor, post(document + "/checkout")));
                if (result == 200) {
                    api.as(actor, post(document + "/cancel-checkout")).andExpect(status().isOk());
                }
                yield result;
            }
            case UPLOAD -> code(api.upload(actor, "{\"documentTypeCode\":\"RECEIPT\",\"title\":\"U\","
                    + "\"branchCode\":\"001\",\"cif\":\"C2\",\"customFields\":{\"amount\":1}}", randomBytes(100),
                    "u.pdf", key()));
            case ADMIN_METADATA -> code(api.as(actor, get("/api/v1/admin/cabinets")));
            case ADMIN_ACCESS -> code(api.as(actor, get("/api/v1/admin/access-rules")));
            default -> throw new IllegalArgumentException(action.name());
        };
    }

    private String currentETag() throws Exception {
        return api.as(owner, get("/api/v1/documents/" + documentId)).andReturn().getResponse().getHeader("ETag");
    }

    private static int expectedSuccess(Action action) {
        return action == Action.UPLOAD ? 201 : 200;
    }

    private static int code(org.springframework.test.web.servlet.ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private UUID addRule(RuleSpec spec) {
        return inTenant(() -> {
            AccessRule rule = rules.save(new AccessRule(null, spec));
            events.publishEvent(new AccessRulesChanged(tenant.id()));
            return rule.getId();
        });
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }
}
