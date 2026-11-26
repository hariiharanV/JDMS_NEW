package com.jmr.jdms.workflow;

import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import com.jmr.jdms.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B-05 {@code /admin/committees}: tenant admins only, validated proposals, maker-checker, deactivation. */
@IntegrationTest
class CommitteeAdminIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder admin1;
    TestJwts.Builder admin2;
    TestJwts.Builder member1;
    TestJwts.Builder member2;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        admin1 = TestJwts.user(tenant.code()).username("admin1").roles("TENANT_ADMIN").branchScope("ALL");
        admin2 = TestJwts.user(tenant.code()).username("admin2").roles("TENANT_ADMIN").branchScope("ALL");
        member1 = member("member1");
        member2 = member("member2");
    }

    @Test
    void onlyTenantAdminsManageCommittees() throws Exception {
        for (String role : new String[] {"SECURITY_ADMIN", "CHECKER", "COMMITTEE_MEMBER", "MAKER"}) {
            TestJwts.Builder other = TestJwts.user(tenant.code()).username(role.toLowerCase()).roles(role)
                    .branchScope("ALL");
            api.as(other, get("/api/v1/admin/committees")).andExpect(status().isForbidden());
            api.as(other, json(post("/api/v1/admin/committees"), body("CREDIT_COMMITTEE", 1, member1)))
                    .andExpect(status().isForbidden());
        }
        api.as(admin1, get("/api/v1/admin/committees")).andExpect(status().isOk());
    }

    @Test
    void proposalsAreValidated() throws Exception {
        api.as(admin1, json(post("/api/v1/admin/committees"), "{\"code\":\"x\",\"name\":\"\",\"minQuorum\":3,"
                + "\"members\":[{\"userId\":\"" + member1.subject() + "\"},{\"userId\":\"" + member1.subject()
                + "\"},{\"userId\":\"" + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("CODE_INVALID", "NAME_INVALID",
                        "MEMBER_DUPLICATE", "USER_UNKNOWN")));
        api.as(admin1, json(post("/api/v1/admin/committees"), body("CREDIT_COMMITTEE", 3, member1, member2)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("QUORUM_INVALID"));
    }

    @Test
    void changesNeedASecondAdminAndDeactivationStopsRouting() throws Exception {
        String changeId = idOf(api.as(admin1, json(post("/api/v1/admin/committees"),
                body("CREDIT_COMMITTEE", 1, member1))).andExpect(status().isAccepted())
                .andExpect(header().exists("Location")));
        api.as(admin1, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isForbidden());
        api.as(admin2, post("/api/v1/admin/pending-changes/" + changeId + "/approve")).andExpect(status().isOk());

        String committeeId = DocumentsApi.json(api.as(admin1, get("/api/v1/admin/committees")).andReturn()
                .getResponse().getContentAsString()).at("/items/0/id").asString();
        String etag = api.as(admin1, get("/api/v1/admin/committees/" + committeeId)).andReturn().getResponse()
                .getHeader("ETag");
        String update = idOf(api.as(admin1, json(put("/api/v1/admin/committees/" + committeeId).header("If-Match",
                etag), "{\"name\":\"Credit committee\",\"minQuorum\":2,\"status\":\"ACTIVE\",\"members\":[{\"userId\":"
                + "\"" + member1.subject() + "\"},{\"userId\":\"" + member2.subject() + "\"}]}"))
                .andExpect(status().isAccepted()));
        api.as(admin2, post("/api/v1/admin/pending-changes/" + update + "/approve")).andExpect(status().isOk());
        api.as(admin1, get("/api/v1/admin/committees/" + committeeId))
                .andExpect(jsonPath("$.minQuorum").value(2))
                .andExpect(jsonPath("$.members[*].userId").value(containsInAnyOrder(member1.subject(),
                        member2.subject())))
                .andExpect(header().string("ETag", org.hamcrest.Matchers.not(etag)));

        testMetadata.addApprovalType(tenant, "BOARD_RESOLUTION", "committee-approval");
        TestJwts.Builder maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        String resolution = "{\"documentTypeCode\":\"BOARD_RESOLUTION\",\"title\":\"R\",\"branchCode\":\"001\"}";
        api.upload(maker, resolution, randomBytes(50), "r.pdf", key()).andExpect(status().isCreated());

        String current = api.as(admin1, get("/api/v1/admin/committees/" + committeeId)).andReturn().getResponse()
                .getHeader("ETag");
        String deactivate = idOf(api.as(admin1, delete("/api/v1/admin/committees/" + committeeId)
                .header("If-Match", current)).andExpect(status().isAccepted()));
        api.as(admin2, post("/api/v1/admin/pending-changes/" + deactivate + "/approve")).andExpect(status().isOk());

        api.upload(maker, resolution, randomBytes(50), "r.pdf", key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_ROUTING_FAILED"));
    }

    @Test
    void tenantAdminsListActiveUsersWithTheirRolesToNameMembers() throws Exception {
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreign = TestJwts.user(other.code()).username("foreign").roles("COMMITTEE_MEMBER")
                .branchScope("ALL");
        api.as(foreign, get("/api/v1/me")).andExpect(status().isOk());
        TestJwts.Builder client = TestJwts.client(tenant.code(), "crm").roles("INTEGRATION_CLIENT").branchScope("ALL");
        api.as(client, get("/integration/v1/documents?cif=X")).andReturn(); // a client profile, never a member

        api.as(admin1, get("/api/v1/admin/committees/candidates")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'member1')].roles[0]").value(hasItem("COMMITTEE_MEMBER")))
                .andExpect(jsonPath("$[?(@.username == 'member1')].id").value(hasItem(member1.subject())))
                .andExpect(jsonPath("$[*].username").value(not(hasItem("foreign"))))
                .andExpect(jsonPath("$[*].username").value(not(hasItem("service-account-crm"))));
        for (String role : new String[] {"SECURITY_ADMIN", "CHECKER", "MAKER"}) {
            TestJwts.Builder denied = TestJwts.user(tenant.code()).username(role.toLowerCase()).roles(role)
                    .branchScope("ALL");
            api.as(denied, get("/api/v1/admin/committees/candidates")).andExpect(status().isForbidden());
        }
    }

    TestJwts.Builder member(String username) throws Exception {
        TestJwts.Builder member = TestJwts.user(tenant.code()).username(username).roles("COMMITTEE_MEMBER")
                .branchScope("ALL");
        api.as(member, get("/api/v1/me")).andExpect(status().isOk()); // creates the user profile
        return member;
    }

    static String body(String code, int quorum, TestJwts.Builder... members) {
        StringBuilder list = new StringBuilder();
        for (TestJwts.Builder member : members) {
            list.append(list.isEmpty() ? "" : ",").append("{\"userId\":\"").append(member.subject())
                    .append("\",\"designation\":\"Member\"}");
        }
        return "{\"code\":\"" + code + "\",\"name\":\"Credit committee\",\"minQuorum\":" + quorum + ",\"members\":["
                + list + "]}";
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String idOf(ResultActions result) throws Exception {
        return DocumentsApi.json(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }
}
