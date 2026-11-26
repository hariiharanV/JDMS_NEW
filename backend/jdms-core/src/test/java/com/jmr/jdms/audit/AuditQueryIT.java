package com.jmr.jdms.audit;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * B-11 audit query API: filters, paging and tenant scope for {@code VIEW_AUDIT} holders; the document audit
 * endpoint; every read of the trail is itself audited; everyone else is refused.
 */
@IntegrationTest
class AuditQueryIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate jdbc;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder securityAdmin;
    String documentId;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("RESTRICTED");
        securityAdmin = admin(tenant);
        JsonNode document = api.uploadReceipt(maker, randomBytes(64), "CIF-AUDIT");
        documentId = document.get("id").asString();
        api.download(maker, documentId, document.at("/currentVersion/id").asString()).andExpect(status().isOk());
    }

    @Test
    void filtersSelectEventsNewestFirst() throws Exception {
        JsonNode page = query("?resourceType=DOCUMENT&resourceId=" + documentId);
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.at("/items/0/action").asString()).isEqualTo("DOCUMENT_DOWNLOADED");
        assertThat(page.at("/items/1/action").asString()).isEqualTo("DOCUMENT_UPLOADED");
        assertThat(page.at("/items/0/seq").asLong()).isGreaterThan(page.at("/items/1/seq").asLong());
        assertThat(page.at("/items/0/actorId").asString()).isEqualTo(maker.subject());
        assertThat(page.at("/items/0/hash").asString()).hasSize(64);
        assertThat(page.at("/items/0/requestId").asString()).isNotBlank();

        assertThat(query("?action=DOCUMENT_UPLOADED&actorId=" + maker.subject()).get("total").asLong()).isEqualTo(1);
        assertThat(query("?resourceId=" + documentId + "&outcome=DENIED").get("total").asLong()).isZero();
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(query("?resourceId=" + documentId + "&from=" + future).get("total").asLong()).isZero();
        assertThat(query("?resourceId=" + documentId + "&to=" + future).get("total").asLong()).isEqualTo(2);

        JsonNode first = query("?resourceId=" + documentId + "&size=1");
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("total").asLong()).isEqualTo(2);
        assertThat(query("?resourceId=" + documentId + "&size=1&page=1").at("/items/0/action").asString())
                .isEqualTo("DOCUMENT_UPLOADED");
    }

    @Test
    void readingTheTrailIsAudited() throws Exception {
        query("?resourceId=" + documentId);

        JsonNode reads = query("?action=AUDIT_QUERIED&actorId=" + securityAdmin.subject());
        assertThat(reads.get("total").asLong()).isEqualTo(1); // the second query is recorded after it runs
        assertThat(reads.at("/items/0/details/resourceId").asString()).isEqualTo(documentId);
        assertThat(reads.at("/items/0/details/returned").asInt()).isEqualTo(2);
    }

    @Test
    void deniedEventsCanBeFound() throws Exception {
        api.as(maker, get("/api/v1/audit")).andExpect(status().isForbidden());

        JsonNode denials = query("?action=ACCESS_DENIED&outcome=DENIED&actorId=" + maker.subject());
        assertThat(denials.get("total").asLong()).isEqualTo(1);
    }

    @Test
    void anotherTenantsEventsAreInvisible() throws Exception {
        TestJwts.Builder otherAdmin = admin(testTenants.create("001"));
        api.as(otherAdmin, get("/api/v1/audit?resourceId=" + documentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        api.as(otherAdmin, get("/api/v1/audit?size=200")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].resourceId", everyItem(is(org.hamcrest.Matchers.not(documentId)))));
    }

    @Test
    void invalidQueriesAreRefused() throws Exception {
        Instant now = Instant.now();
        api.as(securityAdmin, get("/api/v1/audit?from=" + now + "&to=" + now.minusSeconds(60)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.as(securityAdmin, get("/api/v1/audit?size=201")).andExpect(status().isBadRequest());
        api.as(securityAdmin, get("/api/v1/audit?outcome=MAYBE")).andExpect(status().isBadRequest());
    }

    @Test
    void onlyViewAuditHoldersMayQuery() throws Exception {
        for (String role : new String[] {"MAKER", "CHECKER", "VIEWER", "RECORDS_OFFICER", "TENANT_ADMIN"}) {
            TestJwts.Builder user = TestJwts.user(tenant.code()).username("u-" + role).roles(role).branchScope("ALL");
            api.as(user, get("/api/v1/audit")).andExpect(status().isForbidden());
        }
        api.as(securityAdmin, get("/api/v1/audit")).andExpect(status().isOk());
    }

    @Test
    void documentAuditForAuditorsOnly() throws Exception {
        api.as(securityAdmin, get("/api/v1/documents/" + documentId + "/audit")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("DOCUMENT_UPLOADED"))
                .andExpect(jsonPath("$[*].action", hasItems("DOCUMENT_UPLOADED", "DOCUMENT_DOWNLOADED")));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = ? "
                + "and resource_type = 'DOCUMENT' and resource_id = ?", Long.class, tenant.id(), "AUDIT_QUERIED",
                documentId)).isEqualTo(1);

        // the maker sees the document but may not read its audit trail
        api.as(maker, get("/api/v1/documents/" + documentId + "/audit")).andExpect(status().isForbidden());
        TestJwts.Builder otherBranch = TestJwts.user(tenant.code()).username("viewer2").roles("VIEWER")
                .branchScope("002");
        api.as(otherBranch, get("/api/v1/documents/" + documentId + "/audit")).andExpect(status().isNotFound());
        api.as(admin(testTenants.create("001")), get("/api/v1/documents/" + documentId + "/audit"))
                .andExpect(status().isNotFound());
        api.as(securityAdmin, get("/api/v1/documents/" + UUID.randomUUID() + "/audit"))
                .andExpect(status().isNotFound());
    }

    private JsonNode query(String parameters) throws Exception {
        return json(api.as(securityAdmin, get("/api/v1/audit" + parameters)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private static TestJwts.Builder admin(CreatedTenant tenant) {
        return TestJwts.user(tenant.code()).username("sec").roles("SECURITY_ADMIN").branchScope("ALL")
                .clearance("RESTRICTED");
    }
}
