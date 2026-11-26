package com.jmr.jdms.metadata;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestMetadata.Model;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * B-08 read endpoints for the UI: the tenant's metadata model (cabinets, folders, document types with fields and LOV
 * options), the caller's tenant-wide permissions and the actions allowed on an opened document.
 */
@IntegrationTest
class CatalogIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;

    DocumentsApi api;
    CreatedTenant tenant;
    Model model;
    TestJwts.Builder maker;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        model = testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("RESTRICTED");
    }

    @Test
    void theMetadataModelIsReadableByEveryUserOfTheTenant() throws Exception {
        api.as(maker, get("/api/v1/cabinets")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("DOCS", "SMALL")));
        api.as(maker, get("/api/v1/cabinets/" + model.docsCabinetId() + "/folders")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Inbox")).andExpect(jsonPath("$[0].path").value("/Inbox"));

        JsonNode types = json(api.as(maker, get("/api/v1/document-types?cabinetId=" + model.docsCabinetId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode receipt = null;
        for (JsonNode type : types) {
            if (type.get("code").asString().equals("RECEIPT")) {
                receipt = type;
            }
        }
        assertThat(receipt).isNotNull();
        JsonNode currency = null;
        for (JsonNode field : receipt.get("fields")) {
            if (field.get("key").asString().equals("currency")) {
                currency = field;
            }
        }
        assertThat(currency).isNotNull();
        assertThat(currency.get("dataType").asString()).isEqualTo("LOV");
        assertThat(currency.at("/options/0/code").asString()).isEqualTo("OMR");
        assertThat(currency.at("/options/0/labelAr").asString()).isEqualTo("ريال");
        assertThat(currency.at("/options/1/code").asString()).isEqualTo("USD");
    }

    @Test
    void anotherTenantsModelIsInvisible() throws Exception {
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder stranger = TestJwts.user(other.code()).username("maker").roles("MAKER").branchScope("ALL");
        api.as(stranger, get("/api/v1/cabinets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()")
                .value(0));
        api.as(stranger, get("/api/v1/cabinets/" + model.docsCabinetId() + "/folders"))
                .andExpect(status().isNotFound());
        api.as(stranger, get("/api/v1/document-types")).andExpect(jsonPath("$.length()").value(0));
        api.as(maker, get("/api/v1/cabinets/" + UUID.randomUUID() + "/folders")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cabinets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/permissions")).andExpect(status().isUnauthorized());
    }

    @Test
    void permissionsFollowTheCallersRules() throws Exception {
        api.as(maker, get("/api/v1/me/permissions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions", hasItem("UPLOAD")))
                .andExpect(jsonPath("$.actions", not(hasItem("ADMIN_ACCESS"))))
                .andExpect(jsonPath("$.actions", not(hasItem("VIEW_AUDIT"))));
        TestJwts.Builder securityAdmin = TestJwts.user(tenant.code()).username("sec").roles("SECURITY_ADMIN")
                .branchScope("ALL");
        api.as(securityAdmin, get("/api/v1/me/permissions"))
                .andExpect(jsonPath("$.actions", containsInAnyOrder("ADMIN_ACCESS", "VIEW_AUDIT")));
    }

    @Test
    void anOpenedDocumentListsTheCallersAllowedActions() throws Exception {
        String id = api.uploadReceipt(maker, randomBytes(50), "CIF-CAT").get("id").asString();
        TestJwts.Builder viewer = TestJwts.user(tenant.code()).username("viewer").roles("VIEWER").branchScope("001")
                .clearance("RESTRICTED");

        api.as(maker, get("/api/v1/documents/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.allowedActions", containsInAnyOrder("VIEW_CONTENT", "DOWNLOAD",
                        "EDIT_METADATA", "CHECK_OUT", "DELETE")));
        api.as(viewer, get("/api/v1/documents/" + id))
                .andExpect(jsonPath("$.allowedActions", containsInAnyOrder("VIEW_CONTENT")));

        // browsing by cabinet
        api.as(maker, get("/api/v1/documents?cabinetId=" + model.docsCabinetId()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        api.as(maker, get("/api/v1/documents?cabinetId=" + model.smallCabinetId())).andExpect(jsonPath("$.total")
                .value(0));
    }
}
