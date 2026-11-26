package com.jmr.jdms.identity;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** B-04 delegation and separation of duties: time-bound, never more than the delegator holds, no re-delegation. */
@IntegrationTest
class DelegationIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder viewer;
    TestJwts.Builder secAdmin;
    String documentId;
    String versionId;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        viewer = TestJwts.user(tenant.code()).username("viewer").roles("VIEWER").branchScope("001")
                .clearance("CONFIDENTIAL");
        secAdmin = TestJwts.user(tenant.code()).username("sec").roles("SECURITY_ADMIN").branchScope("ALL");
        api.as(viewer, get("/api/v1/me")).andExpect(status().isOk());
        var body = api.uploadReceipt(maker, randomBytes(200), "C1");
        documentId = body.get("id").asString();
        versionId = body.at("/currentVersion/id").asString();
    }

    @Test
    void approvedDelegationGivesTheDelegatorsRoleUntilRevoked() throws Exception {
        api.download(viewer, documentId, versionId).andExpect(status().isForbidden());

        String change = proposeDelegation(maker, viewer.subject(), "MAKER", 7).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String changeId = json(change).get("id").asString();
        api.download(viewer, documentId, versionId).andExpect(status().isForbidden()); // not approved yet

        api.as(viewer, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isNotFound()); // the delegate cannot even see it, let alone approve
        api.as(secAdmin, post("/api/v1/admin/pending-changes/" + changeId + "/approve")).andExpect(status().isOk());

        api.as(viewer, get("/api/v1/me")).andExpect(jsonPath("$.delegatedRoles").value(contains("MAKER")));
        api.download(viewer, documentId, versionId).andExpect(status().isOk());

        String delegationId = json(api.as(viewer, get("/api/v1/admin/delegations")).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asString();
        api.as(maker, post("/api/v1/admin/delegations/" + delegationId + "/revoke")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        api.download(viewer, documentId, versionId).andExpect(status().isForbidden());
    }

    @Test
    void delegatedRolesCannotBeDelegatedAgainAndOnlyHeldRolesCanBeDelegated() throws Exception {
        approve(proposeDelegation(maker, viewer.subject(), "MAKER", 7));
        TestJwts.Builder third = TestJwts.user(tenant.code()).username("third").roles("VIEWER").branchScope("001");
        api.as(third, get("/api/v1/me"));

        proposeDelegation(viewer, third.subject(), "MAKER", 7).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("ROLE_NOT_HELD"));
        proposeDelegation(viewer, third.subject(), "CHECKER", 7).andExpect(status().isUnprocessableContent());
        proposeDelegation(maker, third.subject(), "MAKER", 91).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DELEGATION_TOO_LONG"));
        proposeDelegation(maker, maker.subject(), "MAKER", 7).andExpect(status().isUnprocessableContent());
    }

    @Test
    void neitherPartyCanApproveADelegation() throws Exception {
        TestJwts.Builder secAdmin2 = TestJwts.user(tenant.code()).username("sec2").roles("SECURITY_ADMIN")
                .branchScope("ALL");
        api.as(secAdmin2, get("/api/v1/me"));
        String changeId = json(proposeDelegation(secAdmin, secAdmin2.subject(), "SECURITY_ADMIN", 3)
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        api.as(secAdmin2, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANGE_APPROVER_EXCLUDED"));
    }

    @Test
    void aDelegationNeverGivesMoreThanTheDelegatorStillHolds() throws Exception {
        approve(proposeDelegation(maker, viewer.subject(), "MAKER", 7));
        api.download(viewer, documentId, versionId).andExpect(status().isOk());

        // the delegator lost MAKER in Keycloak; their next login mirrors it
        api.as(TestJwts.user(tenant.code()).subject(java.util.UUID.fromString(maker.subject())).username("maker")
                .roles("VIEWER").branchScope("001"), get("/api/v1/me")).andExpect(status().isOk());

        api.download(viewer, documentId, versionId).andExpect(status().isForbidden());
    }

    private void approve(ResultActions proposal) throws Exception {
        String changeId = json(proposal.andExpect(status().isAccepted()).andReturn().getResponse()
                .getContentAsString()).get("id").asString();
        api.as(secAdmin, post("/api/v1/admin/pending-changes/" + changeId + "/approve")).andExpect(status().isOk());
    }

    private ResultActions proposeDelegation(TestJwts.Builder from, String toUserId, String role, int days)
            throws Exception {
        Instant validTo = Instant.now().plus(days, ChronoUnit.DAYS);
        return api.as(from, post("/api/v1/admin/delegations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toUserId\":\"" + toUserId + "\",\"roleCode\":\"" + role + "\",\"validTo\":\"" + validTo
                        + "\",\"reason\":\"Annual leave\"}"));
    }
}
