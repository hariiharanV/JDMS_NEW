package com.jmr.jdms.document;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static com.jmr.jdms.support.DocumentsApi.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.MultipartBody;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * B-12 import API (ADR-0020): legacy documents arrive with all versions, history and per-document permissions,
 * checked against the tool's checksums, idempotent by legacy ID, for the migration client only.
 */
@IntegrationTest
class LegacyImportIT {

    static final String BASE = "/integration/v1/migration";

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate jdbc;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder migration;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        migration = client(tenant);
    }

    @Test
    void aLegacyDocumentArrivesWithItsVersionsHistoryAndPermissions() throws Exception {
        byte[] v1 = randomBytes(300);
        byte[] v2 = randomBytes(400);
        JsonNode created = json(importDocument(migration, metadata("L-1001", "ACTIVE", v1, v2), v1, v2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyImported").value(false))
                .andExpect(jsonPath("$.versions[0].number").value("1.0"))
                .andExpect(jsonPath("$.versions[1].number").value("2.0"))
                .andExpect(jsonPath("$.versions[1].sha256").value(sha256(v2)))
                .andExpect(jsonPath("$.historyRecords").value(3))
                .andExpect(jsonPath("$.rules").value(1))
                .andReturn().getResponse().getContentAsString());
        String id = created.get("documentId").asString();

        assertThat(jdbc.queryForMap("select status, current_version_id, version_lock from document where id = ?::uuid",
                id)).as("stored row").containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForObject("select current_version_id::text from document where id = ?::uuid",
                String.class, id)).isEqualTo(created.at("/versions/1/versionId").asString());
        TestJwts.Builder checker = TestJwts.user(tenant.code()).username("checker").roles("CHECKER")
                .branchScope("001").clearance("RESTRICTED");
        api.as(checker, get("/api/v1/documents/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sourceSystem").value("MIGRATION"))
                .andExpect(jsonPath("$.cif").value("CIF-L1"))
                .andExpect(jsonPath("$.customFields.amount").value(125.5))
                .andExpect(jsonPath("$.currentVersion.number").value("2.0"));
        api.as(checker, get("/api/v1/documents/" + id + "/history"))
                .andExpect(jsonPath("$[*].actorName", containsInAnyOrder("ahmed", "fatima", "fatima")))
                .andExpect(jsonPath("$[2].decision").value("APPROVE"));
        api.download(checker, id, created.at("/versions/0/versionId").asString()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select legacy_created_by from document where id = ?::uuid", String.class, id))
                .isEqualTo("ahmed");
        assertThat(jdbc.queryForObject("select count(*) from job where tenant_id = ? and type = 'SCAN' "
                + "and payload ->> 'documentId' = ?", Integer.class, tenant.id(), id)).as("every version scanned")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = ? "
                + "and resource_id = ?", Integer.class, tenant.id(), "DOCUMENT_IMPORTED", id)).isEqualTo(1);

        // the legacy per-document permission: a role that sees nothing else sees this document
        TestJwts.Builder legacyAuditor = TestJwts.user(tenant.code()).username("auditor").roles("LEGACY_AUDITOR")
                .branchScope("002").clearance("RESTRICTED");
        api.as(legacyAuditor, get("/api/v1/documents/" + id)).andExpect(status().isOk());
        api.as(legacyAuditor, get("/api/v1/documents/" + id + "/versions/" + created.at("/versions/0/versionId")
                .asString() + "/content")).andExpect(status().isForbidden());
    }

    @Test
    void reRunsReturnTheEarlierImportWithoutReadingFiles() throws Exception {
        byte[] v1 = randomBytes(200);
        String first = json(importDocument(migration, metadata("L-2001", "ACTIVE", v1), v1)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("documentId").asString();

        importDocument(migration, metadata("L-2001", "ACTIVE", v1), v1).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyImported").value(true))
                .andExpect(jsonPath("$.documentId").value(first))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.historyRecords").value(3));
        assertThat(jdbc.queryForObject("select count(*) from document where tenant_id = ? and legacy_document_id = ?",
                Integer.class, tenant.id(), "L-2001")).isEqualTo(1);

        // the same legacy ID in another bank is another document
        CreatedTenant other = testTenants.create("001", "002");
        testMetadata.create(other);
        importDocument(client(other), metadata("L-2001", "ACTIVE", v1), v1).andExpect(status().isCreated());
    }

    @Test
    void deletedLegacyDocumentsGoToTheRecycleBin() throws Exception {
        byte[] v1 = randomBytes(100);
        String id = json(importDocument(migration, metadata("L-3001", "DELETED", v1), v1)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DELETED"))
                .andReturn().getResponse().getContentAsString()).get("documentId").asString();
        TestJwts.Builder records = TestJwts.user(tenant.code()).username("records").roles("RECORDS_OFFICER")
                .branchScope("ALL").clearance("RESTRICTED");
        api.as(records, post("/api/v1/documents/" + id + "/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aFileThatChangedAfterDecryptionIsRefusedAndNothingIsKept() throws Exception {
        byte[] declared = randomBytes(100);
        byte[] sent = randomBytes(100);
        importDocument(migration, metadata("L-4001", "ACTIVE", declared), sent)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
        importDocument(migration, metadata("L-4002", "ACTIVE", declared, declared), declared)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VERSION_FILES_MISMATCH"));
        assertThat(jdbc.queryForObject("select count(*) from document where tenant_id = ? and legacy_document_id "
                + "like 'L-400%'", Integer.class, tenant.id())).isZero();
    }

    @Test
    void invalidMetadataIsReportedFieldByField() throws Exception {
        byte[] v1 = randomBytes(50);
        String bad = metadata("L-5001", "ARCHIVED", v1).replace("\"documentTypeCode\":\"RECEIPT\"",
                "\"documentTypeCode\":\"NO_SUCH\"");
        importDocument(migration, bad, v1).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("status",
                        "documentTypeCode")));
        String wrongPermission = metadata("L-5002", "ACTIVE", v1).replace("[\"VIEW_METADATA\"]",
                "[\"ADMIN_ACCESS\"]");
        importDocument(migration, wrongPermission, v1).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("PERMISSION_INVALID"));
    }

    @Test
    void onlyTheMigrationClientImports() throws Exception {
        byte[] v1 = randomBytes(50);
        TestJwts.Builder flexcube = TestJwts.client(tenant.code(), "fcubs").roles("INTEGRATION_CLIENT")
                .branchScope("ALL").clearance("RESTRICTED");
        importDocument(flexcube, metadata("L-6001", "ACTIVE", v1), v1).andExpect(status().isForbidden());
        TestJwts.Builder person = TestJwts.user(tenant.code()).username("p").roles("MIGRATION").branchScope("ALL");
        importDocument(person, metadata("L-6002", "ACTIVE", v1), v1).andExpect(status().isForbidden());
        api.as(flexcube, post(BASE + "/access-check").contentType(MediaType.APPLICATION_JSON)
                .content(accessCheck(List.of(UUID.randomUUID()), "VIEWER", "002"))).andExpect(status().isForbidden());
    }

    @Test
    void theAccessCheckAnswersForAHypotheticalUser() throws Exception {
        byte[] v1 = randomBytes(50);
        String id = json(importDocument(migration, metadata("L-7001", "ACTIVE", v1), v1)
                .andReturn().getResponse().getContentAsString()).get("documentId").asString();
        UUID document = UUID.fromString(id);

        api.as(migration, post(BASE + "/access-check").contentType(MediaType.APPLICATION_JSON)
                .content(accessCheck(List.of(document), "CHECKER", "001"))).andExpect(status().isOk())
                .andExpect(jsonPath("$['" + id + "']", containsInAnyOrder("VIEW_METADATA", "VIEW_CONTENT",
                        "DOWNLOAD")));
        api.as(migration, post(BASE + "/access-check").contentType(MediaType.APPLICATION_JSON)
                .content(accessCheck(List.of(document), "VIEWER", "002"))).andExpect(status().isOk())
                .andExpect(jsonPath("$['" + id + "'].length()").value(0));

        // another bank's migration client learns nothing about this document
        CreatedTenant other = testTenants.create("001");
        api.as(client(other), post(BASE + "/access-check").contentType(MediaType.APPLICATION_JSON)
                .content(accessCheck(List.of(document), "CHECKER", "001"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private ResultActions importDocument(TestJwts.Builder caller, String metadata, byte[]... files) throws Exception {
        MultipartBody body = MultipartBody.create().json("metadata", metadata);
        for (int i = 0; i < files.length; i++) {
            body.file("file", "scan-v" + (i + 1) + ".pdf", "application/pdf", files[i]);
        }
        return api.as(caller, post(BASE + "/documents").contentType(body.contentType()).content(body.bytes()));
    }

    private static String metadata(String legacyId, String status, byte[]... versions) throws Exception {
        StringBuilder declared = new StringBuilder();
        for (int i = 0; i < versions.length; i++) {
            declared.append(i == 0 ? "" : ",").append("{\"fileName\":\"scan-v").append(i + 1)
                    .append(".pdf\",\"mimeType\":\"application/pdf\",\"sha256\":\"").append(sha256(versions[i]))
                    .append("\",\"createdAt\":\"2019-0").append(i + 1).append("-15T08:30:00Z\",\"createdBy\":\"ahmed\"}");
        }
        return "{\"legacyDocumentId\":\"" + legacyId + "\",\"status\":\"" + status + "\","
                + "\"createdAt\":\"2019-01-15T08:30:00Z\",\"createdBy\":\"ahmed\","
                + "\"documentTypeCode\":\"RECEIPT\",\"title\":\"Legacy receipt\",\"branchCode\":\"001\","
                + "\"cif\":\"CIF-L1\",\"customFields\":{\"amount\":125.5},"
                + "\"versions\":[" + declared + "],"
                + "\"history\":[{\"round\":1,\"stage\":\"Maker\",\"actor\":\"ahmed\",\"decision\":\"SUBMIT\","
                + "\"decidedAt\":\"2019-01-15T08:30:00Z\"},"
                + "{\"round\":1,\"stage\":\"Checker\",\"actor\":\"fatima\",\"decision\":\"RETURN\",\"comment\":\"blurred\","
                + "\"decidedAt\":\"2019-01-15T09:00:00Z\"},"
                + "{\"round\":2,\"stage\":\"Checker\",\"actor\":\"fatima\",\"decision\":\"APPROVE\","
                + "\"decidedAt\":\"2019-02-15T10:00:00Z\"}],"
                + "\"permissions\":[{\"role\":\"LEGACY_AUDITOR\",\"legacyRole\":\"R17\",\"actions\":[\"VIEW_METADATA\"]}]}";
    }

    private static String accessCheck(List<UUID> documents, String role, String branch) {
        return "{\"roles\":[\"" + role + "\"],\"allBranches\":false,\"branchCodes\":[\"" + branch + "\"],"
                + "\"clearance\":\"CONFIDENTIAL\",\"documentIds\":[" + String.join(",", documents.stream()
                .map(id -> "\"" + id + "\"").toList()) + "]}";
    }

    private static TestJwts.Builder client(CreatedTenant tenant) {
        return TestJwts.client(tenant.code(), "jdms-migration").roles("MIGRATION").branchScope("ALL")
                .clearance("RESTRICTED");
    }
}
