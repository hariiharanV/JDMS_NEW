package com.jmr.jdms.access;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * B-04 rule administration: maker-checker, no approving rules that apply to yourself, last-admin guard, and
 * revocation within 60 seconds (immediately on this node through the change event; within the cache TTL — 2 s in
 * tests, 30 s by default — when the event does not reach a node).
 */
@IntegrationTest
class AccessRuleAdminIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate jdbc;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder secAdmin1;
    TestJwts.Builder secAdmin2;
    TestJwts.Builder maker;
    String documentId;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        secAdmin1 = TestJwts.user(tenant.code()).username("sec1").roles("SECURITY_ADMIN").branchScope("ALL");
        secAdmin2 = TestJwts.user(tenant.code()).username("sec2").roles("SECURITY_ADMIN").branchScope("ALL");
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        documentId = api.uploadReceipt(maker, randomBytes(200), "C1").get("id").asString();
    }

    @Test
    void denyRuleTakesEffectImmediatelyAfterApproval() throws Exception {
        String change = propose(secAdmin1, "{\"scopeType\":\"DOCUMENT\",\"scopeId\":\"" + documentId
                + "\",\"principalType\":\"USER\",\"principalId\":\"" + maker.subject()
                + "\",\"actions\":[\"VIEW_METADATA\"],\"effect\":\"DENY\",\"description\":\"Conflict of interest\"}");
        api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isOk()); // pending: no effect yet

        approve(secAdmin1, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_OWN_PROPOSAL"));
        approve(secAdmin2, change).andExpect(status().isOk());

        api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound());
        api.as(secAdmin1, get("/api/v1/admin/access-rules")).andExpect(jsonPath("$.items[?(@.effect == 'DENY')]"
                + ".scopeLabel").value(org.hamcrest.Matchers.contains("Document Receipt")));
    }

    @Test
    void nobodyApprovesARuleThatAppliesToThemselves() throws Exception {
        String change = propose(secAdmin1, "{\"scopeType\":\"TENANT\",\"principalType\":\"ROLE\","
                + "\"principalId\":\"SECURITY_ADMIN\",\"actions\":[\"VIEW_METADATA\"],\"effect\":\"ALLOW\","
                + "\"branchConstraint\":\"ALL\"}");

        approve(secAdmin2, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_APPROVER_EXCLUDED"));
        api.as(secAdmin2, get("/api/v1/admin/pending-changes/" + change)).andExpect(jsonPath("$.canDecide")
                .value(false));
    }

    @Test
    void theLastAdminAccessRuleCannotBeRemoved() throws Exception {
        JsonNode rules = json(api.as(secAdmin1, get("/api/v1/admin/access-rules").param("size", "200")).andReturn()
                .getResponse().getContentAsString()).get("items");
        JsonNode adminRule = null;
        for (JsonNode rule : rules) {
            if (rule.get("principalId").asString().equals("SECURITY_ADMIN")) {
                adminRule = rule;
            }
        }
        assertThat(adminRule).isNotNull();

        api.as(secAdmin1, delete("/api/v1/admin/access-rules/" + adminRule.get("id").asString())
                .header("If-Match", "\"" + adminRule.get("version").asLong() + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_ACCESS_RULE"));
    }

    @Test
    void invalidRulesAreRejected() throws Exception {
        api.as(secAdmin1, post("/api/v1/admin/access-rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"CABINET\",\"scopeId\":\"00000000-0000-7000-8000-000000000000\","
                        + "\"principalType\":\"ROLE\",\"principalId\":\"maker\",\"actions\":[],"
                        + "\"effect\":\"ALLOW\",\"branchConstraint\":\"LIST\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "SCOPE_UNKNOWN", "PRINCIPAL_INVALID", "ACTIONS_REQUIRED", "BRANCH_UNKNOWN")));
    }

    @Test
    void onlySecurityAdminsAdministerRulesAndUsers() throws Exception {
        TestJwts.Builder tenantAdmin = TestJwts.user(tenant.code()).roles("TENANT_ADMIN").branchScope("ALL");
        api.as(tenantAdmin, get("/api/v1/admin/access-rules")).andExpect(status().isForbidden());
        api.as(maker, get("/api/v1/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    void removingAnAllowRuleRevokesAccessOnThisNodeAtOnce() throws Exception {
        api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isOk());
        String makerRuleId = jdbc.queryForObject("select id::text from access_rule where tenant_id = ? "
                + "and principal_id = 'MAKER'", String.class, tenant.id());
        long version = jdbc.queryForObject("select version_lock from access_rule where id = ?::uuid", Long.class,
                makerRuleId);
        String change = json(api.as(secAdmin1, delete("/api/v1/admin/access-rules/" + makerRuleId)
                .header("If-Match", "\"" + version + "\"")).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();

        approve(secAdmin2, change).andExpect(status().isOk());
        api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound());
    }

    @Test
    void ruleUpdatesNeedApproval() throws Exception {
        String rule = "{\"scopeType\":\"DOCUMENT\",\"scopeId\":\"" + documentId + "\",\"principalType\":\"USER\","
                + "\"principalId\":\"" + maker.subject() + "\",\"actions\":[\"DOWNLOAD\"],\"effect\":\"DENY\","
                + "\"description\":\"%s\"}";
        JsonNode created = json(api.as(secAdmin1, post("/api/v1/admin/access-rules").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(rule.formatted("Pending review")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        approve(secAdmin2, created.get("id").asString()).andExpect(status().isOk());
        String ruleId = created.get("subjectId").asString();

        String update = json(api.as(secAdmin1, put("/api/v1/admin/access-rules/" + ruleId).header("If-Match", "\"0\"")
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(rule.formatted("Review closed"))).andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString()).get("id").asString();
        approve(secAdmin2, update).andExpect(status().isOk());
        api.as(secAdmin1, get("/api/v1/admin/access-rules/" + ruleId))
                .andExpect(jsonPath("$.description").value("Review closed"));
    }

    @Test
    void ruleChangesNotSignalledToThisNodeApplyWithinTheCacheTtl() throws Exception {
        api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isOk()); // rules now cached
        Instant removed = Instant.now();
        // another node removed the rule: no event here, only the TTL bounds staleness
        jdbc.update("delete from access_rule where tenant_id = ? and principal_id = 'MAKER'", tenant.id());

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound()));
        assertThat(Duration.between(removed, Instant.now())).isLessThan(Duration.ofSeconds(60));
    }

    private String propose(TestJwts.Builder admin, String body) throws Exception {
        return json(api.as(admin, post("/api/v1/admin/access-rules").header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions approve(TestJwts.Builder admin, String changeId) throws Exception {
        return api.as(admin, post("/api/v1/admin/pending-changes/" + changeId + "/approve"));
    }
}
