package com.jmr.jdms.document;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static com.jmr.jdms.support.DocumentsApi.receipt;
import static com.jmr.jdms.support.DocumentsApi.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.TestcontainersConfiguration.OwnerJdbc;
import com.jmr.jdms.audit.domain.AuditEvent;
import com.jmr.jdms.audit.domain.AuditEventRepository;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.document.domain.DocumentVersion;
import com.jmr.jdms.document.domain.DocumentVersionRepository;
import com.jmr.jdms.document.domain.StorageStatus;
import com.jmr.jdms.document.infra.OrphanBlobReconciler;
import com.jmr.jdms.shared.job.JobRepository;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.MultipartBody;
import com.jmr.jdms.support.S3TestAccess;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * B-03 upload, storage and download: encrypted blob under the documented key format, SHA-256, version 1.0, outbox,
 * limits, idempotency, integrity failures and orphan detection.
 */
@IntegrationTest
class DocumentUploadIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired S3TestAccess s3;
    @Autowired TransactionTemplate transactions;
    @Autowired DocumentVersionRepository versions;
    @Autowired AuditEventRepository auditEvents;
    @Autowired JobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerJdbc ownerJdbc;
    @Autowired OrphanBlobReconciler reconciler;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder viewer;
    TestJwts.Builder client;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        viewer = TestJwts.user(tenant.code()).username("viewer").roles("VIEWER").branchScope("001")
                .clearance("CONFIDENTIAL");
        client = TestJwts.client(tenant.code(), "flexcube").roles("INTEGRATION_CLIENT").branchScope("ALL")
                .clearance("CONFIDENTIAL");
    }

    @Test
    void uploadStoresEncryptedContentAndDownloadReturnsTheSameBytes() throws Exception {
        byte[] content = randomBytes(3 * 1024 * 1024 + 123); // several 1 MiB encryption segments
        MvcResult result = api.upload(maker, receipt("C001", "001"), content, "C:\\scans\\receipt 2026.pdf", key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/documents/")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.documentTypeCode").value("RECEIPT"))
                .andExpect(jsonPath("$.cabinetCode").value("DOCS"))
                .andExpect(jsonPath("$.branchCode").value("001"))
                .andExpect(jsonPath("$.classification").value("INTERNAL"))
                .andExpect(jsonPath("$.cif").value("C001"))
                .andExpect(jsonPath("$.customFields.amount").value(125.500))
                .andExpect(jsonPath("$.customFields.currency").value("OMR"))
                .andExpect(jsonPath("$.currentVersion.number").value("1.0"))
                .andExpect(jsonPath("$.currentVersion.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.currentVersion.storageStatus").value("STORED"))
                .andExpect(jsonPath("$.currentVersion.processingStatus").value("PENDING"))
                .andExpect(jsonPath("$.currentVersion.originalFilename").value("receipt 2026.pdf"))
                .andExpect(jsonPath("$.currentVersion.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.currentVersion.sha256").value(sha256(content)))
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andReturn();
        JsonNode body = json(result.getResponse().getContentAsString());
        String documentId = body.get("id").asString();
        String versionId = body.at("/currentVersion/id").asString();

        MvcResult download = api.download(maker, documentId, versionId)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(content);
        assertThat(actions()).contains("DOCUMENT_UPLOADED", "DOCUMENT_DOWNLOADED");
    }

    @Test
    void blobIsEncryptedUnderAnOpaqueKeyAndBoundToIt() throws Exception {
        byte[] content = ("PLAINTEXT-MARKER " + "x".repeat(5000)).getBytes(StandardCharsets.UTF_8);
        JsonNode body = api.uploadReceipt(maker, content, "C002");
        DocumentVersion version = version(body.at("/currentVersion/id").asString());

        assertThat(version.getBlobKey()).matches(tenant.code()
                + "/\\d{4}/\\d{2}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(version.getBlobKey()).doesNotContain("receipt");
        byte[] stored = s3.read(version.getBlobKey());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("PLAINTEXT-MARKER");
        assertThat(stored.length).isGreaterThan(content.length); // header and authentication tags
        assertThat(version.content().wrappedKey()).isNotBlank();

        // ciphertext moved to another key no longer decrypts: the key is authenticated data
        String otherKey = version.getBlobKey().substring(0, version.getBlobKey().lastIndexOf('/') + 1)
                + UUID.randomUUID();
        s3.write(otherKey, stored);
        JdbcTemplate owner = ownerJdbc.jdbc(); // the application user may not touch triggers
        owner.update("alter table document_version disable trigger trg_document_version_immutable");
        try {
            owner.update("update document_version set blob_key = ? where id = ?", otherKey, version.getId());
        } finally {
            owner.update("alter table document_version enable trigger trg_document_version_immutable");
        }
        api.download(maker, body.get("id").asString(), version.getId().toString())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_INTEGRITY_FAILURE"));
    }

    @Test
    void typeWithApprovalStartsPendingApproval() throws Exception {
        api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"Contract\",\"branchCode\":\"001\"}",
                randomBytes(1000), "contract.pdf", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.classification").value("CONFIDENTIAL"))
                .andExpect(jsonPath("$.currentVersion.approvalStatus").value("PENDING"));
    }

    @Test
    void invalidMetadataIsRejectedBeforeAnythingIsStored() throws Exception {
        int before = s3.keys(tenant.code() + "/").size();

        api.upload(maker, "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\",\"customFields\":"
                + "{\"amount\":\"lots\",\"colour\":\"red\"}}", randomBytes(1000), "r.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "cif", "customFields.amount", "customFields.colour")));
        api.upload(maker, "{\"documentTypeCode\":\"NOPE\",\"branchCode\":\"001\"}", randomBytes(10), "r.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DOCUMENT_TYPE_UNKNOWN"));
        api.upload(maker, "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"999\",\"cif\":\"C\","
                + "\"customFields\":{\"amount\":1}}", randomBytes(10), "r.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BRANCH_UNKNOWN"));

        assertThat(s3.keys(tenant.code() + "/")).hasSize(before);
    }

    @Test
    void classificationAndChannelRules() throws Exception {
        TestJwts.Builder lowClearance = TestJwts.user(tenant.code()).roles("MAKER").branchScope("001")
                .clearance("INTERNAL");
        api.upload(lowClearance, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"C\",\"branchCode\":\"001\"}",
                randomBytes(10), "c.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CLASSIFICATION_ABOVE_CLEARANCE"));
        api.upload(maker, "{\"documentTypeCode\":\"CONTRACT\",\"title\":\"C\",\"branchCode\":\"001\","
                + "\"classification\":\"INTERNAL\"}", randomBytes(10), "c.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CLASSIFICATION_BELOW_DEFAULT"));
        api.upload(client, "{\"documentTypeCode\":\"UI_ONLY\",\"title\":\"U\",\"branchCode\":\"001\"}",
                randomBytes(10), "u.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CHANNEL_NOT_ALLOWED"));
        api.upload(client, receipt("C9", "001"), randomBytes(10), "r.pdf", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceSystem").value("INTEGRATION:flexcube"));
    }

    @Test
    void cabinetSizeLimitQuotaAndEmptyFilesAreEnforced() throws Exception {
        int before = s3.keys(tenant.code() + "/").size();
        String small = "{\"documentTypeCode\":\"SMALL_DOC\",\"title\":\"S\",\"branchCode\":\"001\"}";

        api.upload(maker, small, randomBytes(1024 * 1024 + 1), "big.pdf", key())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        api.upload(maker, small, new byte[0], "empty.pdf", key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_EMPTY"));
        assertThat(s3.keys(tenant.code() + "/")).hasSize(before);

        // quota of the SMALL cabinet is 2 MB
        api.upload(maker, small, randomBytes(1024 * 1024), "a.pdf", key()).andExpect(status().isCreated());
        api.upload(maker, small, randomBytes(1024 * 1024), "b.pdf", key()).andExpect(status().isCreated());
        api.upload(maker, small, randomBytes(10), "c.pdf", key())
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        assertThat(s3.keys(tenant.code() + "/")).hasSize(before + 2);
    }

    /** B-13: the quota row is locked by each upload, so two cannot both take the last of a quota. */
    @Test
    void concurrentUploadsCannotBothTakeTheLastOfAQuota() throws Exception {
        String small = "{\"documentTypeCode\":\"SMALL_DOC\",\"title\":\"S\",\"branchCode\":\"001\"}";
        api.upload(maker, small, randomBytes(1024 * 1024), "a.pdf", key()).andExpect(status().isCreated());

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Integer>> uploads = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        return api.upload(maker, small, randomBytes(768 * 1024), "c" + i + ".pdf", key())
                                .andReturn().getResponse().getStatus();
                    })).toList();
            go.countDown();
            assertThat(List.of(uploads.get(0).get(), uploads.get(1).get())).containsExactlyInAnyOrder(201, 413);
        }
    }

    @Test
    void idempotencyKeyIsRequiredAndReplaysTheOriginalResponse() throws Exception {
        byte[] content = randomBytes(2000);
        api.upload(maker, receipt("C3", "001"), content, "r.pdf", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String key = key();
        String first = api.upload(maker, receipt("C3", "001"), content, "r.pdf", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = api.upload(maker, receipt("C3", "001"), content, "r.pdf", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json(replay).get("id")).isEqualTo(json(first).get("id"));

        api.upload(maker, receipt("C3", "001"), randomBytes(2000), "r.pdf", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        TestJwts.Builder otherMaker = TestJwts.user(tenant.code()).roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        String other = api.upload(otherMaker, receipt("C3", "001"), content, "r.pdf", key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json(other).get("id")).isNotEqualTo(json(first).get("id"));

        // the replay did not leave a second blob behind
        assertThat(inTenant(() -> versions.findAll()).stream().filter(v -> v.getSha256().equals(sha256Safe(content))))
                .hasSize(2);
    }

    @Test
    void sameFileForTheSameCustomerIsFlaggedNotBlocked() throws Exception {
        byte[] content = randomBytes(5000);
        api.uploadReceipt(maker, content, "C4");

        api.upload(maker, receipt("C4", "001"), content, "again.pdf", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warnings[0].code").value("DUPLICATE_CONTENT"))
                .andExpect(jsonPath("$.warnings[0].count").value(1));
        api.upload(maker, receipt("C5", "001"), content, "other-customer.pdf", key())
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void newVersionsAreQueuedForProcessingThroughTheOutbox() throws Exception {
        JsonNode body = api.uploadReceipt(maker, randomBytes(1000), "C6");
        String versionId = body.at("/currentVersion/id").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(inTenant(() -> jobs.findByType(
                JobType.SCAN)).stream().map(job -> job.getPayload())).anySatisfy(payload ->
                        assertThat(payload).contains(versionId).contains(tenant.id().toString())));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from event_publication where serialized_event like ? and completion_date is null",
                Integer.class, "%" + versionId + "%")).isZero());
        assertThat(jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?",
                Integer.class, "%" + versionId + "%")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void corruptedContentIsDetectedMarkedMissingAndAlerted() throws Exception {
        byte[] content = randomBytes(10_000);
        JsonNode body = api.uploadReceipt(maker, content, "C7");
        String documentId = body.get("id").asString();
        DocumentVersion version = version(body.at("/currentVersion/id").asString());
        byte[] stored = s3.read(version.getBlobKey());
        stored[stored.length / 2] ^= 0x01;
        s3.write(version.getBlobKey(), stored);

        api.download(maker, documentId, version.getId().toString())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_INTEGRITY_FAILURE"));

        assertThat(version(version.getId().toString()).getStorageStatus()).isEqualTo(StorageStatus.MISSING);
        assertThat(actions()).contains("STORAGE_INTEGRITY_FAILED");
        api.download(maker, documentId, version.getId().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_UNAVAILABLE"));
    }

    @Test
    void corruptionInALaterSegmentAbortsTheTransferBeforeTheLastBytes() throws Exception {
        byte[] content = randomBytes(3 * 1024 * 1024);
        JsonNode body = api.uploadReceipt(maker, content, "C8");
        DocumentVersion version = version(body.at("/currentVersion/id").asString());
        byte[] stored = s3.read(version.getBlobKey());
        stored[stored.length - 100] ^= 0x01;
        s3.write(version.getBlobKey(), stored);

        Exception failure = null;
        MvcResult result = null;
        try {
            result = api.download(maker, body.get("id").asString(), version.getId().toString()).andReturn();
        } catch (Exception e) {
            failure = e;
        }
        if (result != null) {
            assertThat(result.getResponse().getContentAsByteArray()).isNotEqualTo(content);
            assertThat(result.getResponse().getContentAsByteArray().length).isLessThan(content.length);
        } else {
            assertThat(failure).isNotNull();
        }
        assertThat(version(version.getId().toString()).getStorageStatus()).isEqualTo(StorageStatus.MISSING);
    }

    @Test
    void missingBlobIsMarkedMissing() throws Exception {
        JsonNode body = api.uploadReceipt(maker, randomBytes(1000), "C9");
        DocumentVersion version = version(body.at("/currentVersion/id").asString());
        s3.delete(version.getBlobKey());

        api.download(maker, body.get("id").asString(), version.getId().toString())
                .andExpect(status().isInternalServerError());
        assertThat(version(version.getId().toString()).getStorageStatus()).isEqualTo(StorageStatus.MISSING);
    }

    @Test
    void orphanBlobsAreFoundAndOnlyThem() throws Exception {
        JsonNode body = api.uploadReceipt(maker, randomBytes(1000), "C10");
        String referenced = version(body.at("/currentVersion/id").asString()).getBlobKey();
        String orphan = tenant.code() + "/2026/01/ab/" + UUID.randomUUID();
        s3.write(orphan, randomBytes(100));

        OrphanBlobReconciler.Report report = reconciler.reconcile(Duration.ZERO, false);
        assertThat(report.orphans()).contains(orphan).doesNotContain(referenced);
        assertThat(s3.keys(orphan)).hasSize(1);

        reconciler.reconcile(Duration.ZERO, true);
        assertThat(s3.keys(orphan)).isEmpty();
        assertThat(s3.keys(referenced)).hasSize(1);
    }

    @Test
    void interimAccessRules() throws Exception {
        JsonNode body = api.uploadReceipt(maker, randomBytes(1000), "C11");
        String documentId = body.get("id").asString();
        String versionId = body.at("/currentVersion/id").asString();

        api.as(viewer, get("/api/v1/documents/" + documentId)).andExpect(status().isOk());
        api.download(viewer, documentId, versionId).andExpect(status().isForbidden());
        api.upload(viewer, receipt("C11", "001"), randomBytes(10), "r.pdf", key()).andExpect(status().isForbidden());

        TestJwts.Builder otherBranch = TestJwts.user(tenant.code()).roles("MAKER").branchScope("002")
                .clearance("RESTRICTED");
        api.as(otherBranch, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound());
        api.download(otherBranch, documentId, versionId).andExpect(status().isNotFound());

        TestJwts.Builder publicOnly = TestJwts.user(tenant.code()).roles("MAKER").branchScope("ALL")
                .clearance("PUBLIC");
        api.as(publicOnly, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound());
        api.as(publicOnly, get("/api/v1/documents")).andExpect(jsonPath("$.total").value(0));

        TestJwts.Builder otherTenant = TestJwts.user(testTenants.create("001").code()).roles("MAKER")
                .branchScope("ALL").clearance("RESTRICTED");
        api.as(otherTenant, get("/api/v1/documents/" + documentId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + documentId)).andExpect(status().isUnauthorized());

        api.as(maker, get("/api/v1/documents").param("cif", "C11"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId));
    }

    @Test
    void multipartMustHaveMetadataBeforeTheFile() throws Exception {
        MultipartBody fileFirst = MultipartBody.create().file("file", "r.pdf", "application/pdf", randomBytes(10))
                .json("metadata", receipt("C12", "001"));
        api.as(maker, post("/api/v1/documents").header("Idempotency-Key", key())
                .contentType(fileFirst.contentType()).content(fileFirst.bytes()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("METADATA_REQUIRED"));
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

    private static String sha256Safe(byte[] content) {
        try {
            return sha256(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
