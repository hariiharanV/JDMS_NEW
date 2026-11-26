package com.jmr.jdms.document;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static com.jmr.jdms.support.DocumentsApi.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.document.domain.DocumentVersion;
import com.jmr.jdms.document.domain.DocumentVersionRepository;
import com.jmr.jdms.metadata.domain.DocumentTypeRepository;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.MultipartBody;
import com.jmr.jdms.support.S3TestAccess;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** B-03 versions and check-out (spec §4.3), version immutability and metadata changes. */
@IntegrationTest
class DocumentVersioningIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired S3TestAccess s3;
    @Autowired TransactionTemplate transactions;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentTypeRepository documentTypes;
    @Autowired AuditEventRepository auditEvents;
    @Autowired JdbcTemplate jdbc;

    DocumentsApi api;
    CreatedTenant tenant;
    TestMetadata.Model model;
    TestJwts.Builder maker;
    TestJwts.Builder maker2;
    TestJwts.Builder checker;
    byte[] original;
    String documentId;
    String version10;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001");
        model = testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        maker2 = TestJwts.user(tenant.code()).username("maker2").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        checker = TestJwts.user(tenant.code()).username("checker").roles("CHECKER").branchScope("ALL")
                .clearance("RESTRICTED");
        original = randomBytes(4000);
        JsonNode body = api.uploadReceipt(maker, original, "C100");
        documentId = body.get("id").asString();
        version10 = body.at("/currentVersion/id").asString();
    }

    @Test
    void checkOutDraftsAndCheckInFromTheLatestDraft() throws Exception {
        action(maker, "/checkout").andExpect(status().isOk()).andExpect(jsonPath("$.checkedOutBy").value(maker.subject()));
        action(maker, "/checkout").andExpect(status().isOk()); // repeat by the holder is harmless
        action(maker2, "/checkout").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CHECKED_OUT"));
        draft(maker2, randomBytes(100)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CHECKED_OUT_BY_YOU"));
        // others can still read while it is checked out
        api.download(maker2, documentId, version10).andExpect(status().isOk());

        draft(maker, randomBytes(100)).andExpect(status().isCreated()).andExpect(jsonPath("$.number").value("1.1"))
                .andExpect(jsonPath("$.approvalStatus").value("DRAFT"));
        byte[] latestDraft = randomBytes(200);
        draft(maker, latestDraft).andExpect(jsonPath("$.number").value("1.2"));

        api.as(maker, post("/api/v1/documents/" + documentId + "/checkin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Final\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist())
                .andExpect(jsonPath("$.currentVersion.number").value("2.0"))
                .andExpect(jsonPath("$.currentVersion.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.currentVersion.sha256").value(sha256(latestDraft)))
                .andExpect(jsonPath("$.currentVersion.comment").value("Final"));

        api.as(maker, get("/api/v1/documents/" + documentId + "/versions"))
                .andExpect(jsonPath("$[*].number").value(contains("2.0", "1.2", "1.1", "1.0")));
        assertThat(api.download(maker, documentId, version10).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(original);
        assertThat(actions()).contains("DOCUMENT_CHECKED_OUT", "DOCUMENT_DRAFT_UPLOADED", "DOCUMENT_CHECKED_IN");
    }

    @Test
    void checkInWithAFileAndNothingToCheckIn() throws Exception {
        action(maker, "/checkout").andExpect(status().isOk());
        api.as(maker, post("/api/v1/documents/" + documentId + "/checkin").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_CHECK_IN"));

        byte[] replacement = randomBytes(3000);
        MultipartBody body = MultipartBody.create().field("comment", "Signed copy", null)
                .file("file", "signed.pdf", "application/pdf", replacement);
        api.as(maker, post("/api/v1/documents/" + documentId + "/checkin").contentType(body.contentType())
                .content(body.bytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.number").value("2.0"))
                .andExpect(jsonPath("$.currentVersion.originalFilename").value("signed.pdf"))
                .andExpect(jsonPath("$.currentVersion.sha256").value(sha256(replacement)));
    }

    /** B-13: the quota counter replaces a sum over all versions; it must always equal that sum. */
    @Test
    void cabinetUsageFollowsVersionsAndDiscardedDrafts() throws Exception {
        long before = usage();
        assertThat(before).isEqualTo(sumOfVersions()).isGreaterThanOrEqualTo(4000);
        action(maker, "/checkout").andExpect(status().isOk());
        draft(maker, randomBytes(300)).andExpect(status().isCreated());
        draft(maker, randomBytes(200)).andExpect(status().isCreated());
        assertThat(usage()).isEqualTo(before + 500).isEqualTo(sumOfVersions());

        action(maker, "/cancel-checkout").andExpect(status().isOk());
        assertThat(usage()).isEqualTo(before).isEqualTo(sumOfVersions());
    }

    private long usage() {
        return jdbc.queryForObject("select used_bytes from cabinet_usage where cabinet_id = ?", Long.class,
                model.docsCabinetId());
    }

    private long sumOfVersions() {
        return jdbc.queryForObject("select coalesce(sum(v.size_bytes), 0) from document_version v join document d "
                + "on d.tenant_id = v.tenant_id and d.id = v.document_id where d.cabinet_id = ?", Long.class,
                model.docsCabinetId());
    }

    @Test
    void cancelCheckOutDiscardsDraftsAndTheirBlobs() throws Exception {
        action(maker, "/checkout").andExpect(status().isOk());
        String draftId = json(draft(maker, randomBytes(100)).andReturn().getResponse().getContentAsString())
                .get("id").asString();
        String draftBlob = version(draftId).getBlobKey();

        action(maker2, "/cancel-checkout").andExpect(status().isConflict());
        action(maker, "/cancel-checkout").andExpect(status().isOk()).andExpect(jsonPath("$.checkedOutBy").doesNotExist());

        api.as(maker, get("/api/v1/documents/" + documentId + "/versions"))
                .andExpect(jsonPath("$[*].number").value(contains("1.0")));
        assertThat(s3.keys(draftBlob)).isEmpty();
        action(maker2, "/checkout").andExpect(status().isOk());
    }

    @Test
    void whenApprovalIsRequiredTheNewVersionWaitsAndThePreviousStaysCurrent() throws Exception {
        // the admin assigns an approval process to RECEIPT after the document was created
        TenantScope.run(tenant.id(), () -> transactions.executeWithoutResult(status -> jdbc.update(
                "update document_type set process_key = 'single-approval' where id = ?", model.receiptTypeId())));
        action(maker, "/checkout").andExpect(status().isOk());
        draft(maker, randomBytes(100));

        api.as(maker, post("/api/v1/documents/" + documentId + "/checkin").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.number").value("1.0"));
        api.as(maker, get("/api/v1/documents/" + documentId + "/versions"))
                .andExpect(jsonPath("$[0].number").value("2.0"))
                .andExpect(jsonPath("$[0].approvalStatus").value("PENDING"));
        action(maker, "/checkout").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_PENDING_APPROVAL"));
    }

    @Test
    void restoringAnOldVersionCreatesANewOne() throws Exception {
        action(maker, "/checkout");
        MultipartBody body = MultipartBody.create().file("file", "v2.pdf", "application/pdf", randomBytes(500));
        api.as(maker, post("/api/v1/documents/" + documentId + "/checkin").contentType(body.contentType())
                .content(body.bytes())).andExpect(status().isOk());

        action(maker, "/versions/" + version10 + "/restore")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion.number").value("3.0"))
                .andExpect(jsonPath("$.currentVersion.sha256").value(sha256(original)))
                .andExpect(jsonPath("$.currentVersion.comment").value("Restored from version 1.0"));
        String restoredId = json(api.as(maker, get("/api/v1/documents/" + documentId)).andReturn().getResponse()
                .getContentAsString()).at("/currentVersion/id").asString();
        assertThat(version(restoredId).getBlobKey()).isNotEqualTo(version(version10).getBlobKey());
        assertThat(api.download(maker, documentId, restoredId).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(original);
    }

    @Test
    void versionsAreImmutableInTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("update document_version set sha256 = ? where id = ?::uuid",
                "f".repeat(64), version10)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update document_version set blob_key = 'elsewhere' where id = ?::uuid",
                version10)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from document_version where id = ?::uuid", version10))
                .hasMessageContaining("only drafts can be deleted");
        // status columns may change
        assertThat(jdbc.update("update document_version set processing_status = 'PROCESSING' where id = ?::uuid",
                version10)).isEqualTo(1);
    }

    @Test
    void metadataChangeIsAppliedAndAuditedWithSensitiveValuesMasked() throws Exception {
        String patch = "{\"title\":\"Receipt April\",\"customerName\":\"Sara\","
                + "\"customFields\":{\"note\":\"checked\",\"secret_note\":\"new secret\"}}";
        patchDocument(maker, null, patch).andExpect(status().isPreconditionRequired());
        patchDocument(maker, "\"7\"", patch).andExpect(status().isPreconditionFailed());

        patchDocument(maker, etag(), patch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Receipt April"))
                .andExpect(jsonPath("$.customerName").value("Sara"))
                .andExpect(jsonPath("$.customFields.note").value("checked"))
                .andExpect(jsonPath("$.customFields.amount").value(125.500)) // untouched by the merge patch
                .andExpect(jsonPath("$.cif").value("C100"));

        AuditEvent event = inTenant(() -> auditEvents.findByActionOrderByOccurredAt("DOCUMENT_METADATA_CHANGED"))
                .getLast();
        assertThat(event.getDetails()).contains("Receipt April").contains("checked")
                .doesNotContain("new secret").doesNotContain("hush").contains("[sensitive]");

        patchDocument(maker, etag(), "{\"customFields\":{\"amount\":\"lots\"}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("customFields.amount"));
        patchDocument(maker, etag(), "{\"cif\":null}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("cif")); // required by the type
        patchDocument(maker, etag(), "{\"documentTypeCode\":\"CONTRACT\"}")
                .andExpect(status().isUnprocessableContent());

        action(maker2, "/checkout").andExpect(status().isOk());
        patchDocument(maker, etag(), "{\"title\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CHECKED_OUT"));
    }

    @Test
    void metadataChangeOfATypeRequiringApprovalWaitsForAChecker() throws Exception {
        String contractId = json(api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Loan contract\","
                + "\"branchCode\":\"001\"}", randomBytes(100), "c.pdf", key()).andReturn().getResponse()
                .getContentAsString()).get("id").asString();
        // the upload itself is approved first (B-05): pending documents cannot be changed
        String uploadTask = json(api.as(checker, get("/api/v1/tasks")).andReturn().getResponse().getContentAsString())
                .at("/items/0/id").asString();
        api.as(checker, post("/api/v1/tasks/" + uploadTask + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());
        String etag = api.as(maker, get("/api/v1/documents/" + contractId)).andReturn().getResponse()
                .getHeader("ETag");

        String changeId = json(api.as(maker, patch("/api/v1/documents/" + contractId).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Loan contract (signed)\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.subjectType").value("DOCUMENT_METADATA"))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        api.as(maker, get("/api/v1/documents/" + contractId)).andExpect(jsonPath("$.title").value("Loan contract"));

        api.as(maker, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isForbidden()); // makers do not approve
        TestJwts.Builder tenantAdmin = TestJwts.user(tenant.code()).roles("TENANT_ADMIN").branchScope("ALL");
        api.as(tenantAdmin, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isNotFound()); // document changes are for checkers, not admins
        api.as(checker, get("/api/v1/admin/pending-changes")).andExpect(jsonPath("$.items[0].canDecide").value(true));
        api.as(checker, post("/api/v1/admin/pending-changes/" + changeId + "/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        api.as(maker, get("/api/v1/documents/" + contractId))
                .andExpect(jsonPath("$.title").value("Loan contract (signed)"));
    }

    @Test
    void foldersWithDocumentsCannotBeDeleted() throws Exception {
        api.upload(maker, "{\"documentTypeCode\":\"RECEIPT\",\"title\":\"Filed\",\"branchCode\":\"001\","
                + "\"cif\":\"C101\",\"folderId\":\"" + model.inboxFolderId() + "\",\"customFields\":{\"amount\":1}}",
                randomBytes(100), "f.pdf", key()).andExpect(status().isCreated());
        TestJwts.Builder admin = TestJwts.user(tenant.code()).roles("TENANT_ADMIN").branchScope("ALL");

        api.as(admin, delete("/api/v1/admin/folders/" + model.inboxFolderId()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"));
    }

    private ResultActions action(TestJwts.Builder user, String path) throws Exception {
        return api.as(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/documents/" + documentId + path));
    }

    private ResultActions draft(TestJwts.Builder user, byte[] content) throws Exception {
        MultipartBody body = MultipartBody.create().field("comment", "draft", null)
                .file("file", "draft.pdf", "application/pdf", content);
        return api.as(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/documents/" + documentId + "/versions").header("Idempotency-Key", key())
                .contentType(body.contentType()).content(body.bytes()));
    }

    private ResultActions patchDocument(TestJwts.Builder user, String ifMatch, String json) throws Exception {
        var request = patch("/api/v1/documents/" + documentId).contentType("application/merge-patch+json")
                .content(json);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return api.as(user, request);
    }

    private String etag() throws Exception {
        return api.as(maker, get("/api/v1/documents/" + documentId)).andExpect(header().exists("ETag")).andReturn()
                .getResponse().getHeader("ETag");
    }

    private DocumentVersion version(String versionId) {
        return inTenant(() -> versions.findById(UUID.fromString(versionId)).orElseThrow());
    }

    private List<String> actions() {
        return inTenant(() -> auditEvents.findAll().stream().map(AuditEvent::getAction).toList());
    }

    private <T> T inTenant(Supplier<T> work) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> work.get()));
    }
}
