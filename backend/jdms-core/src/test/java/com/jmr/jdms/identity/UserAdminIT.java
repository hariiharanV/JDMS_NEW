package com.jmr.jdms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authorization matrix and rules for {@code /api/v1/admin/users}: security admins of the same tenant only; changes
 * need If-Match, cannot target yourself, go through maker-checker (a second security admin approves, never the
 * target user), are audited and take effect on the target's next request.
 */
@IntegrationTest
class UserAdminIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TransactionTemplate transactions;
    @Autowired AuditEventRepository auditEvents;

    CreatedTenant tenant;
    TestJwts.Builder admin;
    TestJwts.Builder admin2;
    TestJwts.Builder maker;
    TestJwts.Builder otherTenantUser;

    @BeforeEach
    void createUsers() throws Exception {
        tenant = testTenants.create("001", "002", "003");
        CreatedTenant otherTenant = testTenants.create("001");
        admin = TestJwts.user(tenant.code()).username("secadmin").roles("SECURITY_ADMIN").branchScope("ALL")
                .clearance("RESTRICTED");
        admin2 = TestJwts.user(tenant.code()).username("secadmin2").roles("SECURITY_ADMIN").branchScope("ALL")
                .clearance("RESTRICTED");
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("INTERNAL");
        otherTenantUser = TestJwts.user(otherTenant.code()).username("intruder").roles("SECURITY_ADMIN")
                .branchScope("ALL");
        for (TestJwts.Builder user : List.of(admin, admin2, maker, otherTenantUser)) {
            mvc.perform(get("/api/v1/me").header("Authorization", user.bearer())).andExpect(status().isOk());
        }
    }

    @Test
    void securityAdminListsOnlyUsersOfTheirTenant() throws Exception {
        as(admin, get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].username").value(containsInAnyOrder("secadmin", "secadmin2",
                        "maker")))
                .andExpect(jsonPath("$.items[*].username").value(not(hasItem("intruder"))))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void everyUserOfTheTenantListsItsBranchesAndNoOtherTenants() throws Exception {
        as(maker, get("/api/v1/branches")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(contains("001", "002", "003")))
                .andExpect(jsonPath("$[0].name").isNotEmpty());
        as(otherTenantUser, get("/api/v1/branches")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(contains("001")));
        mvc.perform(get("/api/v1/branches")).andExpect(status().isUnauthorized());
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        as(admin, get("/api/v1/admin/users").param("size", "201")).andExpect(status().isBadRequest());
    }

    @Test
    void securityAdminReadsAUserWithAnETag() throws Exception {
        as(admin, get("/api/v1/admin/users/" + maker.subject()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.username").value("maker"))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")))
                .andExpect(jsonPath("$.clearance").value("INTERNAL"))
                .andExpect(jsonPath("$.roles").value(contains("MAKER")));
    }

    @Test
    void usersOfAnotherTenantDoNotExist() throws Exception {
        as(admin, get("/api/v1/admin/users/" + otherTenantUser.subject()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        as(otherTenantUser, put("/api/v1/admin/users/" + maker.subject() + "/scope").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(scope(true, "[]", "RESTRICTED")))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminsAreDenied() throws Exception {
        as(maker, get("/api/v1/admin/users")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        as(maker, get("/api/v1/admin/users/" + admin.subject())).andExpect(status().isForbidden());
        as(maker, put("/api/v1/admin/users/" + admin.subject() + "/scope").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(scope(true, "[]", "RESTRICTED")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCallersAreUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/admin/users/" + maker.subject() + "/scope")
                .contentType(MediaType.APPLICATION_JSON).content(scope(true, "[]", "RESTRICTED")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeRequiresIfMatch() throws Exception {
        changeScope(null, scope(false, "[\"002\"]", "INTERNAL"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void staleIfMatchIsRejected() throws Exception {
        changeScope("\"7\"", scope(false, "[\"002\"]", "INTERNAL"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }

    @Test
    void unknownBranchesAreRejected() throws Exception {
        changeScope("\"0\"", scope(false, "[\"002\", \"999\"]", "INTERNAL"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BRANCH_UNKNOWN"));
    }

    @Test
    void allBranchesAndAListTogetherAreRejected() throws Exception {
        changeScope("\"0\"", scope(true, "[\"002\"]", "INTERNAL"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidBodyIsABadRequest() throws Exception {
        changeScope("\"0\"", scope(false, "[\"002\"]", "TOP_SECRET")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        changeScope("\"0\"", "{\"branchCodes\":[\"002\"],\"clearance\":\"INTERNAL\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminsCannotChangeTheirOwnAccess() throws Exception {
        as(admin, put("/api/v1/admin/users/" + admin.subject() + "/scope").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(scope(true, "[]", "RESTRICTED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SELF_CHANGE"));
    }

    @Test
    void changeIsProposedApprovedBySecondAdminAuditedAndEffectiveOnTheNextRequest() throws Exception {
        String changeId = JsonMapper.shared().readTree(changeScope("\"0\"", scope(false, "[\"003\", \"002\"]",
                "CONFIDENTIAL"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.subjectType").value("USER_ACCESS"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // nothing changes until a second security admin approves
        mvc.perform(get("/api/v1/me").header("Authorization", maker.bearer()))
                .andExpect(jsonPath("$.branchCodes").value(contains("001")));
        approve(admin, changeId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_OWN_PROPOSAL"));
        approve(admin2, changeId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/api/v1/me").header("Authorization", maker.bearer()))
                .andExpect(jsonPath("$.branchCodes").value(contains("002", "003")))
                .andExpect(jsonPath("$.clearance").value("CONFIDENTIAL"));
        as(admin, get("/api/v1/admin/users/" + maker.subject())).andExpect(header().string("ETag", "\"1\""));

        AuditEvent event = inTenant(() -> auditEvents
                .findByResourceTypeAndResourceIdOrderByOccurredAt("USER_PROFILE", maker.subject())).stream()
                .filter(e -> e.getAction().equals("USER_ACCESS_CHANGED")).findFirst().orElseThrow();
        assertThat(event.getActorType()).isEqualTo(ActorType.USER);
        assertThat(event.getActorId()).isEqualTo(admin2.subject());
        JsonNode details = JsonMapper.shared().readTree(event.getDetails());
        assertThat(details.get("proposedBy").asString()).isEqualTo(admin.subject());
        assertThat(details.at("/before/branchScope").toString()).isEqualTo("[\"001\"]");
        assertThat(details.at("/before/clearance").asString()).isEqualTo("INTERNAL");
        assertThat(details.at("/after/branchScope").toString()).isEqualTo("[\"002\",\"003\"]");
        assertThat(details.at("/after/clearance").asString()).isEqualTo("CONFIDENTIAL");

        // the old ETag no longer applies
        changeScope("\"0\"", scope(true, "[]", "RESTRICTED")).andExpect(status().isPreconditionFailed());
    }

    @Test
    void theUserWhoseAccessChangesCannotApproveIt() throws Exception {
        String changeId = JsonMapper.shared().readTree(as(admin, put("/api/v1/admin/users/" + admin2.subject()
                + "/scope").header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(scope(true, "[]", "RESTRICTED"))).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        approve(admin2, changeId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_APPROVER_EXCLUDED"));
        as(admin2, get("/api/v1/admin/pending-changes/" + changeId)).andExpect(jsonPath("$.canDecide").value(false));
    }

    @Test
    void tenantAdminsDoNotSeeAccessChanges() throws Exception {
        TestJwts.Builder tenantAdmin = TestJwts.user(tenant.code()).roles("TENANT_ADMIN");
        String changeId = JsonMapper.shared().readTree(changeScope("\"0\"", scope(true, "[]", "INTERNAL"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        approve(tenantAdmin, changeId).andExpect(status().isNotFound());
        as(tenantAdmin, get("/api/v1/admin/pending-changes")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void allBranchesCanBeGranted() throws Exception {
        String changeId = JsonMapper.shared().readTree(changeScope("\"0\"", scope(true, "[]", "INTERNAL"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asString();
        approve(admin2, changeId).andExpect(status().isOk());

        as(admin, get("/api/v1/admin/users/" + maker.subject()))
                .andExpect(jsonPath("$.allBranches").value(true))
                .andExpect(jsonPath("$.branchCodes").isEmpty());
    }

    private ResultActions approve(TestJwts.Builder user, String changeId) throws Exception {
        return as(user, post("/api/v1/admin/pending-changes/" + changeId + "/approve"));
    }

    private ResultActions changeScope(String ifMatch, String body) throws Exception {
        var request = put("/api/v1/admin/users/" + maker.subject() + "/scope")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return as(admin, request);
    }

    private ResultActions as(TestJwts.Builder user,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", user.bearer()));
    }

    private static String scope(boolean allBranches, String branchCodesJson, String clearance) {
        return "{\"allBranches\":" + allBranches + ",\"branchCodes\":" + branchCodesJson + ",\"clearance\":\""
                + clearance + "\"}";
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }
}
