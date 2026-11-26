package com.jmr.jdms.document;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.document.infra.OrphanBlobReconciler;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.S3TestAccess;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-06 in the core: processing results reported by the worker (simulated here through the job table, exactly as the
 * worker enqueues them) are applied to versions; renditions are served as preview and thumbnail; an infected version
 * is quarantined and its uploader notified; duplicates add nothing; dead jobs are visible and retryable.
 */
@IntegrationTest
class ProcessingResultsIT {

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JobStore jobs;
    @Autowired EnvelopeEncryption encryption;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper jsonMapper;
    @Autowired S3TestAccess s3;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrphanBlobReconciler reconciler;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;
    TestJwts.Builder viewer;
    String documentId;
    String versionId;
    byte[] original;

    @BeforeEach
    void setUp() throws Exception {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
        viewer = TestJwts.user(tenant.code()).username("viewer").roles("VIEWER").branchScope("001")
                .clearance("CONFIDENTIAL");
        original = randomBytes(2000);
        JsonNode body = api.uploadReceipt(maker, original, "C1");
        documentId = body.get("id").asString();
        versionId = body.at("/currentVersion/id").asString();
    }

    @Test
    void resultsAreAppliedAndRenditionsServedInline() throws Exception {
        byte[] pdf = "%PDF-1.7 rendition".getBytes(StandardCharsets.US_ASCII);
        byte[] png = randomBytes(300);
        api.as(viewer, get(versionPath() + "/preview")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"));

        report(ProcessingStep.SCAN, ProcessingOutcome.CLEAN, null, null, null, false);
        report(ProcessingStep.EXTRACT, ProcessingOutcome.DONE, "application/msword", null,
                List.of(rendition("TEXT", "text/plain; charset=UTF-8", "extracted text".getBytes())), false);
        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "application/msword", 3,
                List.of(rendition("PDF", "application/pdf", pdf), rendition("THUMBNAIL", "image/png", png)), true);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(version(maker).get("processingStatus")
                .asString()).isEqualTo("DONE"));
        JsonNode version = version(maker);
        assertThat(version.get("detectedMimeType").asString()).isEqualTo("application/msword");
        assertThat(version.get("pageCount").asInt()).isEqualTo(3);

        api.as(viewer, get(versionPath() + "/preview")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"preview\""))
                .andExpect(content().bytes(pdf));
        api.as(viewer, get(versionPath() + "/thumbnail")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andExpect(content().bytes(png));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = ?",
                Integer.class, tenant.id(), "DOCUMENT_PREVIEWED")).isEqualTo(1);
        // bank systems get the current version's preview through the integration API
        TestJwts.Builder client = TestJwts.client(tenant.code(), "crm").roles("INTEGRATION_CLIENT").branchScope("ALL")
                .clearance("CONFIDENTIAL");
        api.as(client, get("/integration/v1/documents/" + documentId + "/preview")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andExpect(content().bytes(pdf));
        assertThat(jdbc.queryForObject("select count(*) from audit_event where tenant_id = ? and action = ?",
                Integer.class, tenant.id(), "VERSION_PROCESSED")).isEqualTo(1);

        // denied: no VIEW_CONTENT, other branch, other tenant
        TestJwts.Builder admin = TestJwts.user(tenant.code()).username("admin").roles("TENANT_ADMIN")
                .branchScope("ALL");
        api.as(admin, get(versionPath() + "/preview")).andExpect(status().isNotFound());
        TestJwts.Builder otherBranch = TestJwts.user(tenant.code()).username("viewer2").roles("VIEWER")
                .branchScope("002").clearance("CONFIDENTIAL");
        api.as(otherBranch, get(versionPath() + "/thumbnail")).andExpect(status().isNotFound());
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreign = TestJwts.user(other.code()).username("viewer").roles("VIEWER").branchScope("ALL")
                .clearance("RESTRICTED");
        api.as(foreign, get(versionPath() + "/preview")).andExpect(status().isNotFound());
    }

    @Test
    void aPdfOriginalIsItsOwnPreview() throws Exception {
        api.as(viewer, get(versionPath() + "/thumbnail")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THUMBNAIL_UNAVAILABLE"));
        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "application/pdf", 1,
                List.of(rendition("THUMBNAIL", "image/png", randomBytes(100))), true);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(version(maker).get("processingStatus")
                .asString()).isEqualTo("DONE"));

        api.as(viewer, get(versionPath() + "/preview")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andExpect(content().bytes(original));
    }

    @Test
    void infectedVersionsAreQuarantinedAndTheUploaderNotified() throws Exception {
        report(ProcessingStep.SCAN, ProcessingOutcome.INFECTED, null, null, null, true, "Eicar-Test-Signature");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(version(maker).get("processingStatus")
                .asString()).isEqualTo("QUARANTINED"));
        api.download(maker, documentId, versionId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_QUARANTINED"));
        api.as(viewer, get(versionPath() + "/preview")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOCUMENT_QUARANTINED"));
        assertThat(jdbc.queryForObject("select details ->> 'signature' from audit_event where tenant_id = ? and "
                + "action = 'VERSION_QUARANTINED'", String.class, tenant.id())).isEqualTo("Eicar-Test-Signature");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> api.as(maker, get("/api/v1/me/notifications"))
                .andExpect(jsonPath("$.items[0].type").value("VERSION_QUARANTINED"))
                .andExpect(jsonPath("$.items[0].documentId").value(documentId)));
        String notificationId = json(api.as(maker, get("/api/v1/me/notifications")).andReturn().getResponse()
                .getContentAsString()).at("/items/0/id").asString();
        api.as(viewer, get("/api/v1/me/notifications")).andExpect(jsonPath("$.total").value(0));
        api.as(viewer, post("/api/v1/me/notifications/" + notificationId + "/read")).andExpect(status().isNotFound());
        api.as(maker, post("/api/v1/me/notifications/" + notificationId + "/read")).andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        // nothing derived from an infected file is kept, even if a late result arrives
        RenditionBlob late = rendition("THUMBNAIL", "image/png", randomBytes(100));
        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "image/png", 1, List.of(late), true);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(s3.keys(late.blobKey())).isEmpty());
        assertThat(version(maker).get("processingStatus").asString()).isEqualTo("QUARANTINED");
        api.as(viewer, get(versionPath() + "/thumbnail")).andExpect(status().isForbidden());
    }

    @Test
    void duplicateResultsAddNoSecondRendition() throws Exception {
        RenditionBlob first = rendition("THUMBNAIL", "image/png", randomBytes(100));
        RenditionBlob duplicate = rendition("THUMBNAIL", "image/png", randomBytes(100));
        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "image/png", 1, List.of(first), true);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(renditions()).isEqualTo(1));

        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "image/png", 1, List.of(duplicate), true);
        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "image/png", 1, List.of(first), true); // replayed

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(s3.keys(duplicate.blobKey())).isEmpty());
        assertThat(renditions()).isEqualTo(1);
        assertThat(s3.keys(first.blobKey())).hasSize(1);
        // rendition blobs are referenced: the orphan reconciler keeps them
        assertThat(reconciler.reconcile(Duration.ZERO, false).orphans()).doesNotContain(first.blobKey());
    }

    @Test
    void aFailedVersionIsDoneOnceARetriedPipelineCompletes() throws Exception {
        report(ProcessingStep.EXTRACT, ProcessingOutcome.FAILED, null, null, null, true, "Tika could not parse");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(version(maker).get("processingStatus")
                .asString()).isEqualTo("FAILED"));

        report(ProcessingStep.RENDER, ProcessingOutcome.DONE, "application/pdf", 2, null, true);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(version(maker).get("processingStatus")
                .asString()).isEqualTo("DONE"));
    }

    @Test
    void deadJobsAreVisibleToTenantAdminsAndCanBeRetried() throws Exception {
        UUID jobId = transactions.execute(status -> {
            UUID id = jobs.enqueue(new NewJob(tenant.id(), JobType.EXTRACT.name(), "{\"versionId\":\"" + versionId
                    + "\"}"), 2);
            jobs.dead(id, "HttpTimeoutException: request timed out");
            return id;
        });
        TestJwts.Builder admin = TestJwts.user(tenant.code()).username("admin").roles("TENANT_ADMIN")
                .branchScope("ALL");

        api.as(admin, get("/api/v1/admin/jobs?status=DEAD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(jobId.toString()))
                .andExpect(jsonPath("$.items[0].lastError").value("HttpTimeoutException: request timed out"));
        api.as(maker, get("/api/v1/admin/jobs?status=DEAD")).andExpect(status().isForbidden());
        api.as(maker, post("/api/v1/admin/jobs/" + jobId + "/retry")).andExpect(status().isForbidden());
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreignAdmin = TestJwts.user(other.code()).username("admin").roles("TENANT_ADMIN")
                .branchScope("ALL");
        api.as(foreignAdmin, get("/api/v1/admin/jobs?status=DEAD")).andExpect(jsonPath("$.total").value(0));
        api.as(foreignAdmin, post("/api/v1/admin/jobs/" + jobId + "/retry")).andExpect(status().isNotFound());

        api.as(admin, post("/api/v1/admin/jobs/" + jobId + "/retry")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.attempts").value(0));
        api.as(admin, post("/api/v1/admin/jobs/" + jobId + "/retry")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_NOT_RETRYABLE"));
    }

    // ---- helpers

    String versionPath() {
        return "/api/v1/documents/" + documentId + "/versions/" + versionId;
    }

    JsonNode version(TestJwts.Builder user) throws Exception {
        JsonNode versions = json(api.as(user, get("/api/v1/documents/" + documentId + "/versions")).andReturn()
                .getResponse().getContentAsString());
        return versions.get(0);
    }

    int renditions() {
        return jdbc.queryForObject("select count(*) from rendition where tenant_id = ? and version_id = ?",
                Integer.class, tenant.id(), UUID.fromString(versionId));
    }

    /** Stores a rendition blob as the worker does. */
    RenditionBlob rendition(String kind, String mimeType, byte[] bytes) {
        StoredBlob blob = encryption.store(tenant.id(), tenant.code(), new ByteArrayInputStream(bytes),
                Long.MAX_VALUE);
        return new RenditionBlob(kind, mimeType, blob.blobKey(), blob.sha256(), blob.sizeBytes(), blob.wrappedKey(),
                blob.keyRef());
    }

    void report(ProcessingStep step, ProcessingOutcome outcome, String detected, Integer pages,
            List<RenditionBlob> renditions, boolean last) {
        report(step, outcome, detected, pages, renditions, last, null);
    }

    void report(ProcessingStep step, ProcessingOutcome outcome, String detected, Integer pages,
            List<RenditionBlob> renditions, boolean last, String detail) {
        ProcessingResult result = new ProcessingResult(tenant.id(), UUID.fromString(documentId),
                UUID.fromString(versionId), step, outcome, detail, detected, pages, renditions, false, last);
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(tenant.id(),
                JobType.PROCESSING_RESULT.name(), jsonMapper.writeValueAsString(result)), 5));
    }
}
