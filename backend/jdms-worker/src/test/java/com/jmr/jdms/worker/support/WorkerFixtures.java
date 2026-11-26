package com.jmr.jdms.worker.support;

import com.jmr.jdms.contracts.CaptureJobPayload;
import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.VersionJobPayload;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobStore;
import com.jmr.jdms.platform.jobs.NewJob;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test data for the worker: tenants and versions (with their encrypted content) written directly to the core's
 * tables, as the core would. Versions are inserted without their document (foreign keys are not checked for these
 * fixture rows): the worker only ever reads tenant and version.
 */
@Component
public class WorkerFixtures {

    public record Version(UUID tenantId, String tenantCode, UUID documentId, UUID versionId, StoredBlob blob) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EnvelopeEncryption encryption;
    private final JobStore jobs;
    private final JsonMapper jsonMapper;
    private final WorkerContainers.OwnerJdbc owner;

    WorkerFixtures(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, EnvelopeEncryption encryption,
            JobStore jobs, JsonMapper jsonMapper, WorkerContainers.OwnerJdbc owner) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.encryption = encryption;
        this.jobs = jobs;
        this.jsonMapper = jsonMapper;
    }

    /** A new tenant and a version holding {@code content}, with its SCAN job queued (as the core does). */
    public Version upload(byte[] content, String claimedMimeType) {
        UUID tenantId = UUID.randomUUID();
        String code = "T" + tenantId.toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        StoredBlob blob = encryption.store(tenantId, code, new ByteArrayInputStream(content), Long.MAX_VALUE);
        Timestamp now = Timestamp.from(Instant.now());
        // as the owner: the version's document row is left out, which only the owner may do (replication role)
        owner.transactions().executeWithoutResult(status -> {
            NamedParameterJdbcTemplate jdbc = owner.jdbc();
            jdbc.update("""
                    insert into tenant (id, code, name, status, default_locale, settings, created_at, created_by,
                                        updated_at, updated_by, version_lock)
                    values (:id, :code, 'Test', 'ACTIVE', 'en', '{}'::jsonb, :now, 'test', :now, 'test', 0)
                    """, Map.of("id", tenantId, "code", code, "now", now));
            jdbc.getJdbcTemplate().execute("set local session_replication_role = replica");
            jdbc.update("""
                    insert into document_version (id, tenant_id, document_id, major, minor, approval_status, blob_key,
                        sha256, size_bytes, mime_type, original_filename, storage_status, processing_status,
                        index_status, encrypted_data_key, key_ref, created_at, created_by, updated_at, updated_by,
                        version_lock)
                    values (:id, :tenant, :document, 1, 0, 'APPROVED', :blobKey, :sha, :size, :mime, 'file',
                        'STORED', 'PENDING', 'PENDING', :key, :keyRef, :now, :uploader, :now, :uploader, 0)
                    """, Map.ofEntries(Map.entry("id", versionId), Map.entry("tenant", tenantId),
                    Map.entry("document", documentId), Map.entry("blobKey", blob.blobKey()),
                    Map.entry("sha", blob.sha256()), Map.entry("size", blob.sizeBytes()),
                    Map.entry("mime", claimedMimeType), Map.entry("key", blob.wrappedKey()),
                    Map.entry("keyRef", blob.keyRef()), Map.entry("now", now),
                    Map.entry("uploader", UUID.randomUUID().toString())));
        });
        transactions.executeWithoutResult(status -> {
            jobs.enqueue(new NewJob(tenantId, JobType.SCAN.name(), jsonMapper.writeValueAsString(
                    new VersionJobPayload(tenantId, documentId, versionId))), 2);
        });
        return new Version(tenantId, code, documentId, versionId, blob);
    }

    public record Capture(UUID tenantId, String tenantCode, UUID captureId, StoredBlob blob) {
    }

    /** A custom field of the capture's document type: key, English label, type, list values (code, label). */
    public record Field(String key, String label, String dataType, Map<String, String> lovValues) {
    }

    /**
     * A new tenant and a capture (B-15) holding {@code content} for a document type with these custom fields, with
     * its CAPTURE job queued (as the core does). Parent rows the worker does not read are left out.
     */
    public Capture capture(byte[] content, Field... fields) {
        UUID tenantId = UUID.randomUUID();
        String code = "T" + tenantId.toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID captureId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        StoredBlob blob = encryption.store(tenantId, code, new ByteArrayInputStream(content), Long.MAX_VALUE);
        Timestamp now = Timestamp.from(Instant.now());
        owner.transactions().executeWithoutResult(status -> {
            NamedParameterJdbcTemplate jdbc = owner.jdbc();
            jdbc.update("""
                    insert into tenant (id, code, name, status, default_locale, settings, created_at, created_by,
                                        updated_at, updated_by, version_lock)
                    values (:id, :code, 'Test', 'ACTIVE', 'en', '{}'::jsonb, :now, 'test', :now, 'test', 0)
                    """, Map.of("id", tenantId, "code", code, "now", now));
            jdbc.getJdbcTemplate().execute("set local session_replication_role = replica");
            jdbc.update("""
                    insert into capture (id, tenant_id, document_type_id, branch_id, classification, status,
                        blob_key, sha256, size_bytes, encrypted_data_key, key_ref, ocr, suggestion_count, expires_at,
                        created_at, created_by, updated_at, updated_by, version_lock)
                    values (:id, :tenant, :type, :branch, 'INTERNAL', 'PENDING', :blobKey, :sha, :size, :key, :keyRef,
                        false, 0, :expires, :now, 'test', :now, 'test', 0)
                    """, Map.ofEntries(Map.entry("id", captureId), Map.entry("tenant", tenantId),
                    Map.entry("type", typeId), Map.entry("branch", UUID.randomUUID()),
                    Map.entry("blobKey", blob.blobKey()), Map.entry("sha", blob.sha256()),
                    Map.entry("size", blob.sizeBytes()), Map.entry("key", blob.wrappedKey()),
                    Map.entry("keyRef", blob.keyRef()), Map.entry("expires", Timestamp.from(Instant.now()
                            .plusSeconds(3600))), Map.entry("now", now)));
            int order = 0;
            for (Field field : fields) {
                String lovCode = field.lovValues().isEmpty() ? null : "LOV_" + field.key().toUpperCase();
                jdbc.update("""
                        insert into field_definition (id, tenant_id, document_type_id, field_key, label_en, label_ar,
                            data_type, mandatory, max_length, lov_code, searchable, facet, sensitive, display_order,
                            display_row, created_at, created_by, updated_at, updated_by, version_lock)
                        values (:id, :tenant, :type, :key, :label, null, :dataType, false, null, :lov, false, false,
                            false, :order, 1, :now, 'test', :now, 'test', 0)
                        """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("tenant", tenantId).addValue("type", typeId)
                        .addValue("key", field.key()).addValue("label", field.label())
                        .addValue("dataType", field.dataType()).addValue("lov", lovCode).addValue("order", ++order)
                        .addValue("now", now));
                if (lovCode != null) {
                    UUID lovId = UUID.randomUUID();
                    jdbc.update("""
                            insert into lov (id, tenant_id, code, name, created_at, created_by, updated_at,
                                updated_by, version_lock)
                            values (:id, :tenant, :code, 'List', :now, 'test', :now, 'test', 0)
                            """, Map.of("id", lovId, "tenant", tenantId, "code", lovCode, "now", now));
                    int position = 0;
                    for (Map.Entry<String, String> value : field.lovValues().entrySet()) {
                        jdbc.update("""
                                insert into lov_value (id, tenant_id, lov_id, code, label_en, label_ar,
                                    display_order, active, created_at, created_by, updated_at, updated_by,
                                    version_lock)
                                values (:id, :tenant, :lov, :code, :label, null, :order, true, :now, 'test', :now,
                                    'test', 0)
                                """, Map.of("id", UUID.randomUUID(), "tenant", tenantId, "lov", lovId,
                                "code", value.getKey(), "label", value.getValue(), "order", ++position, "now", now));
                    }
                }
            }
        });
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(tenantId, JobType.CAPTURE.name(),
                jsonMapper.writeValueAsString(new CaptureJobPayload(tenantId, captureId))), 2));
        return new Capture(tenantId, code, captureId, blob);
    }

    /** The capture results reported for the capture, in order. */
    public List<CaptureResult> results(Capture capture) {
        return jdbc.queryForList("""
                select payload::text from job where tenant_id = :tenant and type = 'CAPTURE_RESULT'
                   and payload ->> 'captureId' = :capture order by created_at, id
                """, Map.of("tenant", capture.tenantId(), "capture", capture.captureId().toString()), String.class)
                .stream().map(json -> jsonMapper.readValue(json, CaptureResult.class)).toList();
    }

    /** Queues another CAPTURE job for the capture (a duplicate delivery). */
    public void readAgain(Capture capture) {
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(capture.tenantId(),
                JobType.CAPTURE.name(), jsonMapper.writeValueAsString(new CaptureJobPayload(capture.tenantId(),
                        capture.captureId()))), 2));
    }

    /** Marks the capture as read, as the core does when it applies a result. */
    public void markRead(Capture capture) {
        jdbc.update("update capture set status = 'DONE', blob_key = null where tenant_id = :tenant and id = :id",
                Map.of("tenant", capture.tenantId(), "id", capture.captureId()));
    }

    /** Queues another SCAN for the version (a duplicate delivery). */
    public void scanAgain(Version version) {
        transactions.executeWithoutResult(status -> jobs.enqueue(new NewJob(version.tenantId(), JobType.SCAN.name(),
                jsonMapper.writeValueAsString(new VersionJobPayload(version.tenantId(), version.documentId(),
                        version.versionId()))), 2));
    }

    /** The processing results reported for the version, in order. */
    public List<ProcessingResult> results(Version version) {
        return jdbc.queryForList("""
                select payload::text from job where tenant_id = :tenant and type = 'PROCESSING_RESULT'
                   and payload ->> 'versionId' = :version order by created_at, id
                """, Map.of("tenant", version.tenantId(), "version", version.versionId().toString()), String.class)
                .stream().map(json -> jsonMapper.readValue(json, ProcessingResult.class)).toList();
    }

    /** Gives the version's jobs of one type more attempts (outage tests: the test properties allow two). */
    public void allowAttempts(Version version, String type, int maxAttempts) {
        jdbc.update("update job set max_attempts = :max where tenant_id = :tenant and type = :type",
                Map.of("max", maxAttempts, "tenant", version.tenantId(), "type", type));
    }

    /** Status and last error of the version's jobs of one type. */
    public List<Map<String, Object>> jobs(Version version, String type) {
        return jdbc.queryForList("""
                select status, attempts, last_error from job where tenant_id = :tenant and type = :type
                   and payload ->> 'versionId' = :version order by created_at
                """, Map.of("tenant", version.tenantId(), "type", type, "version", version.versionId().toString()));
    }

    /** Records renditions as the core does when it applies a result. */
    public void applyRenditions(Version version, ProcessingResult result, String processingStatus) {
        Timestamp now = Timestamp.from(Instant.now());
        transactions.executeWithoutResult(status -> {
            result.renditions().forEach(rendition -> jdbc.update("""
                    insert into rendition (id, tenant_id, version_id, kind, mime_type, blob_key, sha256, size_bytes,
                        encrypted_data_key, key_ref, ocr, created_at, created_by, updated_at, updated_by,
                        version_lock)
                    values (:id, :tenant, :version, :kind, :mime, :blobKey, :sha, :size, :key, :keyRef, false, :now,
                        'test', :now, 'test', 0)
                    """, Map.ofEntries(Map.entry("id", UUID.randomUUID()), Map.entry("tenant", version.tenantId()),
                    Map.entry("version", version.versionId()), Map.entry("kind", rendition.kind()),
                    Map.entry("mime", rendition.mimeType()), Map.entry("blobKey", rendition.blobKey()),
                    Map.entry("sha", rendition.sha256()), Map.entry("size", rendition.sizeBytes()),
                    Map.entry("key", rendition.wrappedKey()), Map.entry("keyRef", rendition.keyRef()),
                    Map.entry("now", now))));
            if (processingStatus != null) {
                jdbc.update("update document_version set processing_status = :status where id = :id",
                        Map.of("status", processingStatus, "id", version.versionId()));
            }
        });
    }

    public InputStream open(UUID tenantId, StoredBlob blob) {
        return encryption.open(tenantId, blob);
    }
}
