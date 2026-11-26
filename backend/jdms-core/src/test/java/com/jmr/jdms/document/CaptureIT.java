package com.jmr.jdms.document;

import static com.jmr.jdms.support.DocumentsApi.json;
import static com.jmr.jdms.support.DocumentsApi.key;
import static com.jmr.jdms.support.DocumentsApi.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jmr.jdms.contracts.CaptureJobPayload;
import com.jmr.jdms.contracts.CaptureOutcome;
import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.FieldSuggestion.Confidence;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.document.domain.CaptureService;
import com.jmr.jdms.document.infra.OrphanBlobReconciler;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.DocumentsApi;
import com.jmr.jdms.support.IntegrationTest;
import com.jmr.jdms.support.MultipartBody;
import com.jmr.jdms.support.S3TestAccess;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-15 in the core (ADR-0024): a file attached for assisted indexing is stored, queued for the worker and, once the
 * worker's result (simulated here through the job table, exactly as the worker enqueues it) is applied, its
 * suggestions are returned to the user who attached it, and only to them. The file is deleted once read, the
 * capture when discarded or expired. Upload permission is required for the chosen type and branch.
 */
@IntegrationTest
class CaptureIT {

    private static final String RECEIPT = "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\"}";

    @Autowired MockMvc mvc;
    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JobStore jobs;
    @Autowired EnvelopeEncryption encryption;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper jsonMapper;
    @Autowired S3TestAccess s3;
    @Autowired JdbcTemplate jdbc;
    @Autowired CaptureService captures;
    @Autowired OrphanBlobReconciler reconciler;

    DocumentsApi api;
    CreatedTenant tenant;
    TestJwts.Builder maker;

    @BeforeEach
    void setUp() {
        api = new DocumentsApi(mvc);
        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        maker = TestJwts.user(tenant.code()).username("maker").roles("MAKER").branchScope("001")
                .clearance("CONFIDENTIAL");
    }

    @Test
    void theWorkersSuggestionsReachTheUserWhoAttachedTheFile() throws Exception {
        byte[] file = randomBytes(3000);
        JsonNode started = json(start(maker, RECEIPT, file, key()).andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.documentTypeCode").value("RECEIPT"))
                .andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(started.get("id").asString());

        // stored encrypted under the tenant's prefix, and queued for the worker with IDs only
        String fileKey = column(id, "blob_key");
        assertThat(fileKey).startsWith(tenant.code() + "/");
        assertThat(s3.read(fileKey)).isNotEqualTo(file);
        assertThat(jsonMapper.readValue(jdbc.queryForObject(
                "select payload::text from job where tenant_id = ? and type = 'CAPTURE'", String.class, tenant.id()),
                CaptureJobPayload.class)).isEqualTo(new CaptureJobPayload(tenant.id(), id));
        api.as(maker, get(path(id))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.suggestions.length()").value(0));

        List<FieldSuggestion> found = List.of(
                new FieldSuggestion("cif", "C25112594", Confidence.HIGH, "CIF No"),
                new FieldSuggestion("customFields.amount", "125.500", Confidence.MEDIUM, "Amount"));
        RenditionBlob suggestions = suggestionsBlob(found);
        report(new CaptureResult(tenant.id(), id, CaptureOutcome.DONE, null, "image/png", true, suggestions, 2));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> api.as(maker, get(path(id)))
                .andExpect(jsonPath("$.status").value("DONE")));
        api.as(maker, get(path(id))).andExpect(jsonPath("$.ocr").value(true))
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].field").value("cif"))
                .andExpect(jsonPath("$.suggestions[0].value").value("C25112594"))
                .andExpect(jsonPath("$.suggestions[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.suggestions[0].label").value("CIF No"))
                .andExpect(jsonPath("$.suggestions[1].field").value("customFields.amount"));
        // the file is not kept once read; the values are only in the encrypted blob, never in a column or the audit
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(s3.keys(fileKey)).isEmpty());
        assertThat(column(id, "blob_key")).isNull();
        assertThat(column(id, "sugg_blob_key")).isEqualTo(suggestions.blobKey());
        assertThat(s3.read(suggestions.blobKey())).asString(StandardCharsets.ISO_8859_1).doesNotContain("C25112594");
        assertThat(jdbc.queryForList("select details::text from audit_event where tenant_id = ? and resource_id = ?",
                String.class, tenant.id(), id.toString())).hasSize(2).noneMatch(details -> details.contains("C2511"));
        assertThat(actions(id)).containsExactly("CAPTURE_STARTED", "CAPTURE_READ");

        // a duplicate delivery of the result changes nothing; its blob is deleted
        RenditionBlob again = suggestionsBlob(found);
        report(new CaptureResult(tenant.id(), id, CaptureOutcome.DONE, null, "image/png", true, again, 2));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(s3.keys(again.blobKey())).isEmpty());
        assertThat(column(id, "sugg_blob_key")).isEqualTo(suggestions.blobKey());
        assertThat(actions(id)).containsExactly("CAPTURE_STARTED", "CAPTURE_READ");

        // the owner discards it: suggestions gone, 404 afterwards
        api.as(maker, delete(path(id)).header("Idempotency-Key", key())).andExpect(status().isNoContent());
        api.as(maker, get(path(id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAPTURE_NOT_FOUND"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(s3.keys(suggestions.blobKey()))
                .isEmpty());
        assertThat(actions(id)).containsExactly("CAPTURE_STARTED", "CAPTURE_READ", "CAPTURE_DISCARDED");
    }

    @Test
    void onlyAUserWhoMayUploadThereCanAttachAndOnlyTheOwnerSeesIt() throws Exception {
        // denied: no UPLOAD (viewer), a branch outside the maker's scope, above the maker's clearance
        TestJwts.Builder viewer = TestJwts.user(tenant.code()).username("viewer").roles("VIEWER").branchScope("001")
                .clearance("CONFIDENTIAL");
        start(viewer, RECEIPT, randomBytes(100), key()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        start(maker, "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"002\"}", randomBytes(100), key())
                .andExpect(status().isForbidden());
        start(maker, "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\",\"classification\":\"RESTRICTED\"}",
                randomBytes(100), key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CLASSIFICATION_ABOVE_CLEARANCE"));
        // nothing was stored for the denied requests
        assertThat(jdbc.queryForObject("select count(*) from capture where tenant_id = ?", Integer.class,
                tenant.id())).isZero();
        assertThat(s3.keys(tenant.code() + "/")).isEmpty();

        UUID id = startedId(maker, RECEIPT);
        // another maker of the same branch, another tenant's maker with the same username: not found
        TestJwts.Builder colleague = TestJwts.user(tenant.code()).username("maker2").roles("MAKER")
                .branchScope("001").clearance("CONFIDENTIAL");
        api.as(colleague, get(path(id))).andExpect(status().isNotFound());
        api.as(colleague, delete(path(id)).header("Idempotency-Key", key())).andExpect(status().isNotFound());
        CreatedTenant other = testTenants.create("001");
        TestJwts.Builder foreign = TestJwts.user(other.code()).username("maker").roles("MAKER").branchScope("ALL")
                .clearance("RESTRICTED");
        api.as(foreign, get(path(id))).andExpect(status().isNotFound());
        api.as(foreign, delete(path(id)).header("Idempotency-Key", key())).andExpect(status().isNotFound());
        api.as(maker, get(path(id))).andExpect(status().isOk());

        // the permission is checked again when the capture is read: a maker who lost the role sees nothing
        TestJwts.Builder demoted = TestJwts.user(tenant.code()).subject(UUID.fromString(maker.subject()))
                .username("maker").roles("VIEWER").branchScope("001").clearance("CONFIDENTIAL");
        api.as(demoted, get(path(id))).andExpect(status().isForbidden());
    }

    @Test
    void anInfectedOrUnreadableFileSuggestsNothingAndIsDeleted() throws Exception {
        UUID infected = startedId(maker, RECEIPT);
        String infectedKey = column(infected, "blob_key");
        UUID failed = startedId(maker, RECEIPT);

        report(new CaptureResult(tenant.id(), infected, CaptureOutcome.INFECTED, "Eicar-Test-Signature", null,
                false, null, 0));
        report(new CaptureResult(tenant.id(), failed, CaptureOutcome.FAILED, "Tika: malformed PDF", null, false,
                null, 0));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            api.as(maker, get(path(infected))).andExpect(jsonPath("$.status").value("INFECTED"))
                    .andExpect(jsonPath("$.suggestions.length()").value(0));
            api.as(maker, get(path(failed))).andExpect(jsonPath("$.status").value("FAILED"));
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(s3.keys(infectedKey)).isEmpty());
        assertThat(jdbc.queryForObject("select details ->> 'signature' from audit_event where tenant_id = ? "
                + "and action = 'CAPTURE_INFECTED'", String.class, tenant.id())).isEqualTo("Eicar-Test-Signature");
        assertThat(actions(failed)).containsExactly("CAPTURE_STARTED", "CAPTURE_FAILED");
    }

    @Test
    void aRepeatedRequestReturnsTheSameCaptureAndPendingCapturesAreLimited() throws Exception {
        byte[] file = randomBytes(500);
        String key = key();
        String first = json(start(maker, RECEIPT, file, key).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
        String second = json(start(maker, RECEIPT, file, key).andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString()).get("id").asString();
        assertThat(second).isEqualTo(first);
        // the same key with another file is refused
        start(maker, RECEIPT, randomBytes(500), key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        start(maker, RECEIPT, file, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        for (int i = 0; i < 4; i++) {
            startedId(maker, RECEIPT);
        }
        start(maker, RECEIPT, randomBytes(100), key()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CAPTURE_LIMIT"));
        assertThat(jdbc.queryForObject("select count(*) from capture where tenant_id = ?", Integer.class,
                tenant.id())).isEqualTo(5);
    }

    @Test
    void invalidChoicesAreRejectedBeforeTheFileIsStored() throws Exception {
        start(maker, "{\"documentTypeCode\":\"NOPE\",\"branchCode\":\"001\"}", randomBytes(100), key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DOCUMENT_TYPE_UNKNOWN"));
        start(maker, "{\"documentTypeCode\":\"RECEIPT\"}", randomBytes(100), key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("BRANCH_REQUIRED"));
        start(maker, "{\"documentTypeCode\":\"RECEIPT\",\"branchCode\":\"001\",\"folderId\":\"" + UUID.randomUUID()
                + "\"}", randomBytes(100), key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("FOLDER_INVALID"));
        // the SMALL cabinet takes files up to 1 MB
        start(maker, "{\"documentTypeCode\":\"SMALL_DOC\",\"branchCode\":\"001\"}", randomBytes(1024 * 1024 + 1),
                key()).andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("select count(*) from capture where tenant_id = ?", Integer.class,
                tenant.id())).isZero();
    }

    @Test
    void expiredCapturesAreGoneAndRemovedWithTheirBlobs() throws Exception {
        UUID id = startedId(maker, RECEIPT);
        String fileKey = column(id, "blob_key");
        assertThat(reconciler.reconcile(Duration.ZERO, false).orphans()).doesNotContain(fileKey);

        jdbc.update("update capture set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        api.as(maker, get(path(id))).andExpect(status().isNotFound());

        int removed = TenantScope.call(tenant.id(), () -> captures.expire(Instant.now()));
        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from capture where id = ?", Integer.class, id)).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(s3.keys(fileKey)).isEmpty());
        assertThat(actions(id)).containsExactly("CAPTURE_STARTED", "CAPTURE_EXPIRED");

        // a result arriving after expiry is dropped with its blob
        RenditionBlob late = suggestionsBlob(List.of(new FieldSuggestion("cif", "C1", Confidence.HIGH, "CIF")));
        report(new CaptureResult(tenant.id(), id, CaptureOutcome.DONE, null, null, false, late, 1));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(s3.keys(late.blobKey())).isEmpty());
    }

    private UUID startedId(TestJwts.Builder user, String metadata) throws Exception {
        return UUID.fromString(json(start(user, metadata, randomBytes(200), key()).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asString());
    }

    private ResultActions start(TestJwts.Builder user, String metadata, byte[] file, String idempotencyKey)
            throws Exception {
        MultipartBody body = MultipartBody.create().json("metadata", metadata)
                .file("file", "scan.png", "image/png", file);
        var request = post("/api/v1/captures").contentType(body.contentType()).content(body.bytes());
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return api.as(user, request);
    }

    private RenditionBlob suggestionsBlob(List<FieldSuggestion> suggestions) {
        StoredBlob blob = encryption.store(tenant.id(), tenant.code(), new ByteArrayInputStream(
                jsonMapper.writeValueAsString(suggestions).getBytes(StandardCharsets.UTF_8)), Long.MAX_VALUE);
        return new RenditionBlob("SUGGESTIONS", "application/json", blob.blobKey(), blob.sha256(), blob.sizeBytes(),
                blob.wrappedKey(), blob.keyRef());
    }

    private void report(CaptureResult result) {
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(tenant.id(),
                JobType.CAPTURE_RESULT.name(), jsonMapper.writeValueAsString(result)), 5));
    }

    private String column(UUID id, String column) {
        Map<String, Object> row = jdbc.queryForMap("select * from capture where id = ?", id);
        return (String) row.get(column);
    }

    private List<String> actions(UUID id) {
        return jdbc.queryForList("select action from audit_event where tenant_id = ? and resource_id = ? "
                + "order by occurred_at, id", String.class, tenant.id(), id.toString());
    }

    private static String path(UUID id) {
        return "/api/v1/captures/" + id;
    }
}
