package com.jmr.jdms.records;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.S3TestAccess;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.time.Instant;
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
 * B-10 records: soft delete and restore (with approval where the type requires it), holds and declared records
 * blocking deletion, retention policies under maker-checker, disposition (prepare, approve, destroy blobs, tombstone,
 * audit) and event-based retention.
 */
@IntegrationTest
class RecordsIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3TestAccess s3;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder checker;
    TestJwts.Builder records1;
    TestJwts.Builder records2;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        testMetadata.create(tenant);
        maker = user("maker", "MAKER", "001");
        checker = user("checker", "CHECKER", "001");
        records1 = user("records1", "RECORDS_OFFICER", "ALL");
        records2 = user("records2", "RECORDS_OFFICER", "ALL");
    }

    @Test
    void deletedDocumentsGoToTheRecycleBinAndAreRestoredToTheirStatus() throws Exception {
        String id = upload("C1", null);

        api.as(maker, delete("/api/v1/documents/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        api.as(records1, get("/api/v1/documents?status=DELETED")).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        api.as(maker, delete("/api/v1/documents/" + id)).andExpect(status().isConflict());

        api.as(maker, post("/api/v1/documents/" + id + "/restore")).andExpect(status().isForbidden()); // no RESTORE
        api.as(records1, post("/api/v1/documents/" + id + "/restore")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        api.as(records1, post("/api/v1/documents/" + id + "/restore")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_DELETED"));
        assertThat(auditCount("DOCUMENT_DELETED")).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_RESTORED")).isEqualTo(1);
    }

    @Test
    void deletionNeedsACheckerWhereTheTypeSaysSo() throws Exception {
        jdbc.update("update document_type set delete_requires_approval = true where tenant_id = ? and code = 'RECEIPT'",
                tenant.id());
        String id = upload("C2", null);

        api.as(maker, delete("/api/v1/documents/" + id)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.subjectType").value("DOCUMENT_DELETE"));
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        String task = json(api.as(checker, get("/api/v1/tasks")).andReturn().getResponse().getContentAsString())
                .at("/items/0/id").asString();
        api.as(checker, post("/api/v1/tasks/" + task + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());

        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.status").value("DELETED"));
        api.as(maker, get("/api/v1/documents/" + id + "/history"))
                .andExpect(jsonPath("$[*].subjectType").value(org.hamcrest.Matchers.hasItem("DELETE")));
    }

    @Test
    void heldOrDeclaredDocumentsCannotBeDeleted() throws Exception {
        String id = upload("C3", null);
        String hold = createHold(records1, "Fraud case 17");
        api.as(maker, post("/api/v1/legal-holds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"reason\":\"y\"}")).andExpect(status().isForbidden()); // officers only
        api.as(records1, post("/api/v1/legal-holds/" + hold + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + id + "\"]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentIds[0]").value(id));
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.onLegalHold").value(true));

        api.as(maker, delete("/api/v1/documents/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_LEGAL_HOLD"));
        api.as(records1, post("/api/v1/legal-holds/" + hold + "/release")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.onLegalHold").value(false));
        api.as(maker, delete("/api/v1/documents/" + id)).andExpect(status().isOk());

        // declared on approval for a type configured as records
        jdbc.update("update document_type set declare_record_on_approval = true where tenant_id = ? and code = "
                + "'CONTRACT'", tenant.id());
        String contract = json(api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Loan\","
                + "\"branchCode\":\"001\"}", randomBytes(100), "c.pdf", key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String task = json(api.as(checker, get("/api/v1/tasks")).andReturn().getResponse().getContentAsString())
                .at("/items/0/id").asString();
        api.as(checker, post("/api/v1/tasks/" + task + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());
        api.as(maker, delete("/api/v1/documents/" + contract)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_DECLARED_RECORD"));
        assertThat(auditCount("RECORD_DECLARED")).isEqualTo(1);
    }

    @Test
    void dueDocumentsAreDisposedAfterApprovalLeavingTombstones() throws Exception {
        createPolicy("{\"code\":\"RCPT_NOW\",\"name\":\"Receipts\",\"triggerType\":\"CREATION_DATE\","
                + "\"periodMonths\":0,\"dispositionAction\":\"DESTROY\",\"documentTypeCodes\":[\"RECEIPT\"]}");
        String disposable = upload("C4", null);
        String held = upload("C5", null);
        String hold = createHold(records1, "Litigation");
        api.as(records1, post("/api/v1/legal-holds/" + hold + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cif\":\"C5\"}")).andExpect(status().isOk());
        String blobKey = jdbc.queryForObject("select blob_key from document_version where document_id = ?",
                String.class, UUID.fromString(disposable));
        assertThat(s3.keys(blobKey)).hasSize(1);

        api.as(maker, post("/api/v1/disposition-batches/prepare")).andExpect(status().isForbidden());
        JsonNode batch = json(api.as(records1, post("/api/v1/disposition-batches/prepare"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(ids(batch)).containsExactly(disposable); // the held one is not due
        api.as(records1, post("/api/v1/disposition-batches/prepare")).andExpect(status().isNoContent());

        api.as(records2, post("/api/v1/disposition-batches/" + batch.get("id").asString() + "/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.items[0].outcome").value("DISPOSED"));

        assertThat(s3.keys(blobKey)).isEmpty();
        api.as(records1, get("/api/v1/documents/" + disposable)).andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.title").value("Disposed document"))
                .andExpect(jsonPath("$.cif").doesNotExist());
        String versionId = jdbc.queryForObject("select id from document_version where document_id = ?",
                String.class, UUID.fromString(disposable));
        api.download(records1, disposable, versionId).andExpect(status().isGone());
        assertThat(jdbc.queryForObject("select sha256_list ->> 0 from tombstone where document_id = ?", String.class,
                UUID.fromString(disposable))).hasSize(64);
        assertThat(jdbc.queryForObject("select storage_status from document_version where document_id = ?",
                String.class, UUID.fromString(disposable))).isEqualTo("DESTROYED");
        assertThat(auditCount("DISPOSITION_PREPARED")).isEqualTo(1);
        assertThat(auditCount("DISPOSITION_EXECUTED")).isEqualTo(1);
        assertThat(auditCount("RECORD_DISPOSED")).isEqualTo(1);
        api.as(maker, get("/api/v1/documents/" + held)).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aHoldPlacedAfterPreparationSkipsTheDocument() throws Exception {
        createPolicy("{\"code\":\"RCPT_NOW\",\"name\":\"Receipts\",\"triggerType\":\"CREATION_DATE\","
                + "\"periodMonths\":0,\"dispositionAction\":\"DESTROY\",\"documentTypeCodes\":[\"RECEIPT\"]}");
        String id = upload("C6", null);
        String batch = json(api.as(records1, post("/api/v1/disposition-batches/prepare")).andReturn().getResponse()
                .getContentAsString()).get("id").asString();
        String hold = createHold(records1, "Late hold");
        api.as(records1, post("/api/v1/legal-holds/" + hold + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + id + "\"]}")).andExpect(status().isOk());

        api.as(records2, post("/api/v1/disposition-batches/" + batch + "/approve"))
                .andExpect(jsonPath("$.items[0].outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.items[0].reason").value("On legal hold"));
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        api.as(records2, post("/api/v1/disposition-batches/" + batch + "/approve")).andExpect(status().isConflict());
    }

    @Test
    void holdItemsCanBeRemovedAndBatchesRejected() throws Exception {
        createPolicy("{\"code\":\"RCPT_NOW\",\"name\":\"Receipts\",\"triggerType\":\"CREATION_DATE\","
                + "\"periodMonths\":0,\"dispositionAction\":\"DESTROY\",\"documentTypeCodes\":[\"RECEIPT\"]}");
        String id = upload("C9", null);
        String hold = createHold(records1, "Mistaken hold");
        api.as(records1, post("/api/v1/legal-holds/" + hold + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + id + "\"]}")).andExpect(status().isOk());
        api.as(records1, delete("/api/v1/legal-holds/" + hold + "/items/" + id)).andExpect(status().isOk());
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.onLegalHold").value(false));

        String batch = json(api.as(records1, post("/api/v1/disposition-batches/prepare"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        api.as(records2, post("/api/v1/disposition-batches/" + batch + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"Keep for the audit\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        api.as(maker, get("/api/v1/documents/" + id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(auditCount("DISPOSITION_REJECTED")).isEqualTo(1);
    }

    @Test
    void accountClosureStartsEventBasedRetention() throws Exception {
        createPolicy("{\"code\":\"ACC_CLOSED\",\"name\":\"Closed accounts\",\"triggerType\":\"EVENT\","
                + "\"eventType\":\"ACCOUNT_CLOSED\",\"periodMonths\":0,\"dispositionAction\":\"REVIEW\","
                + "\"documentTypeCodes\":[\"RECEIPT\"]}");
        String linked = upload("C7", "ACC-77");
        upload("C8", "ACC-88");
        api.as(records1, post("/api/v1/disposition-batches/prepare")).andExpect(status().isNoContent());

        api.as(maker, post("/api/v1/records/events").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        api.as(records1, post("/api/v1/records/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACCOUNT_CLOSED\",\"accountNo\":\"ACC-77\",\"occurredAt\":\""
                        + Instant.now().minusSeconds(60) + "\"}")).andExpect(status().isCreated());

        JsonNode batch = json(api.as(records1, post("/api/v1/disposition-batches/prepare"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(ids(batch)).containsExactly(linked);
    }

    @Test
    void retentionPoliciesNeedASecondRecordsOfficer() throws Exception {
        String change = json(api.as(records1, post("/api/v1/retention-policies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"KYC_10Y\",\"name\":\"KYC\",\"triggerType\":\"EXPIRY_DATE\",\"periodMonths\":120,"
                        + "\"dispositionAction\":\"DESTROY\",\"documentTypeCodes\":[\"RECEIPT\"]}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asString();
        api.as(records1, post("/api/v1/admin/pending-changes/" + change + "/approve")).andExpect(status().isForbidden());
        api.as(maker, get("/api/v1/retention-policies")).andExpect(status().isForbidden());
        api.as(records2, post("/api/v1/admin/pending-changes/" + change + "/approve")).andExpect(status().isOk());

        api.as(records1, get("/api/v1/retention-policies")).andExpect(jsonPath("$[0].code").value("KYC_10Y"))
                .andExpect(jsonPath("$[0].documentTypeCodes[0]").value("RECEIPT"));
        String policyId = json(api.as(records1, get("/api/v1/retention-policies")).andReturn().getResponse()
                .getContentAsString()).at("/0/id").asString();
        String update = json(api.as(records1, put("/api/v1/retention-policies/" + policyId).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"KYC_10Y\",\"name\":\"KYC records\","
                        + "\"triggerType\":\"EXPIRY_DATE\",\"periodMonths\":120,\"dispositionAction\":\"DESTROY\","
                        + "\"documentTypeCodes\":[\"RECEIPT\"]}")).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
        api.as(records2, post("/api/v1/admin/pending-changes/" + update + "/approve")).andExpect(status().isOk());
        api.as(records1, get("/api/v1/retention-policies/" + policyId)).andExpect(jsonPath("$.name").value("KYC records"));
        api.as(records1, post("/api/v1/retention-policies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"BAD\",\"name\":\"x\",\"triggerType\":\"EVENT\",\"periodMonths\":-1,"
                        + "\"dispositionAction\":\"DESTROY\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "EVENT_TYPE_REQUIRED", "PERIOD_INVALID")));
    }

    // ---- helpers

    TestJwts.Builder user(String username, String role, String branch) {
        return TestJwts.user(tenant.code()).username(username).roles(role).branchScope(branch)
                .clearance("RESTRICTED");
    }

    String upload(String cif, String accountNo) throws Exception {
        String metadata = "{\"documentTypeCode\":\"RECEIPT\",\"title\":\"Receipt\",\"branchCode\":\"001\",\"cif\":\""
                + cif + "\"" + (accountNo == null ? "" : ",\"accountNo\":\"" + accountNo + "\"")
                + ",\"customFields\":{\"amount\":10}}";
        return json(api.upload(maker, metadata, randomBytes(300), "r.pdf", key()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    String createHold(TestJwts.Builder officer, String name) throws Exception {
        return json(api.as(officer, post("/api/v1/legal-holds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"reason\":\"Court order\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    void createPolicy(String body) throws Exception {
        String change = json(api.as(records1, post("/api/v1/retention-policies").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        ResultActions approved = api.as(records2, post("/api/v1/admin/pending-changes/" + change + "/approve"));
        approved.andExpect(status().isOk());
    }

    int auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = ?",
                Integer.class, tenant.id(), action);
    }

    static List<String> ids(JsonNode batch) {
        List<String> ids = new java.util.ArrayList<>();
        batch.get("items").forEach(item -> ids.add(item.get("documentId").asString()));
        return ids;
    }
}
