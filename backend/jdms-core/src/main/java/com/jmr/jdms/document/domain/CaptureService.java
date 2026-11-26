package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.CaptureJobPayload;
import com.jmr.jdms.contracts.CaptureOutcome;
import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.idempotency.IdempotencyStore;
import com.jmr.jdms.shared.job.JobQueue;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assisted indexing (B-15, ADR-0024): a file attached on the upload screen is stored as a short-lived capture and read
 * by the worker (antivirus, text, OCR, field suggestions), so the form can be filled in before the document is
 * uploaded. The core never parses the file (CLAUDE.md rule 6): it stores it, queues a {@code CAPTURE} job and applies
 * the worker's {@code CAPTURE_RESULT}. A capture belongs to the user who attached it; it needs the upload permission
 * for the chosen type and branch, which is checked again whenever it is read. The file is deleted once read, the
 * capture when it expires or is discarded. The upload itself is unchanged: the user sends the file again with the
 * confirmed metadata.
 */
@Service
public class CaptureService {

    public static final String CAPTURE_NOT_FOUND = "CAPTURE_NOT_FOUND";
    public static final String CAPTURE_LIMIT = "CAPTURE_LIMIT";
    static final String RESOURCE_CAPTURE = "CAPTURE";
    static final String STARTED = "CAPTURE_STARTED";
    static final String READ = "CAPTURE_READ";
    static final String INFECTED = "CAPTURE_INFECTED";
    static final String FAILED = "CAPTURE_FAILED";
    static final String DISCARDED = "CAPTURE_DISCARDED";
    static final String EXPIRED = "CAPTURE_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);
    private static final TypeReference<List<FieldSuggestion>> SUGGESTIONS = new TypeReference<>() {
    };
    private static final int EXPIRY_BATCH = 200;
    /** Suggestions blobs are small (a few KB); anything larger is not ours. */
    private static final int MAX_SUGGESTIONS_BYTES = 256 * 1024;

    /** What the user chose before attaching the file: the placement the upload permission is checked for. */
    public record CaptureInput(String documentTypeCode, String branchCode, UUID folderId,
            Classification classification) {
    }

    /**
     * A capture as its owner sees it. {@code suggestions} is empty until the status is {@code DONE}.
     */
    public record CaptureView(UUID id, CaptureStatus status, String documentTypeCode, boolean ocr,
            List<FieldSuggestion> suggestions, Instant expiresAt) {
    }

    /** The response of a start request, possibly replayed for the same {@code Idempotency-Key}. */
    public record StartOutcome(int status, UUID captureId, JsonNode body, boolean replayed) {
    }

    /**
     * @param ttl        how long a capture (and its suggestions) is kept
     * @param maxPending captures a user may have being read at once
     */
    public record Settings(Duration ttl, int maxPending) {
    }

    private final CaptureRepository captures;
    private final MetadataResolver resolver;
    private final MetadataCatalog catalog;
    private final BranchDirectory branches;
    private final DocumentAccess access;
    private final ContentWriter contentWriter;
    private final ContentStore contentStore;
    private final JobQueue jobs;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotency;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Settings settings;

    CaptureService(CaptureRepository captures, MetadataResolver resolver, MetadataCatalog catalog,
            BranchDirectory branches, DocumentAccess access, ContentWriter contentWriter, ContentStore contentStore,
            JobQueue jobs, AuditTrail auditTrail, IdempotencyStore idempotency, TransactionTemplate transactions,
            JsonMapper jsonMapper, Settings settings) {
        this.captures = captures;
        this.resolver = resolver;
        this.catalog = catalog;
        this.branches = branches;
        this.access = access;
        this.contentWriter = contentWriter;
        this.contentStore = contentStore;
        this.jobs = jobs;
        this.auditTrail = auditTrail;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.settings = settings;
    }

    /** Checks the choice and permission before the stream is read; nothing is stored when they fail. */
    public StartOutcome start(CaptureInput input, InputStream content, String idempotencyKey) {
        String key = IdempotencyStore.requireKey(idempotencyKey);
        RequestContext caller = RequestContext.require();
        DocumentTypeInfo type = resolver.uploadableType(new DocumentFieldsInput(null, input.documentTypeCode(), null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        Placement placement = placement(type, input, caller);
        access.requireUpload(placement.resource(caller.tenantId(), type, catalog));
        String owner = caller.subjectId().toString();
        if (transactions.execute(status -> captures.countByCreatedByAndStatus(owner, CaptureStatus.PENDING))
                >= settings.maxPending()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, CAPTURE_LIMIT,
                    "Too many files are being read for you; wait for them to finish.");
        }

        StoredContent stored = contentWriter.store(content, type.maxFileSizeMb());
        String requestHash = hash(type.code(), String.valueOf(placement.branchId()),
                String.valueOf(placement.folderId()), placement.classification().name(), stored.sha256());
        try {
            StartOutcome outcome = transactions.execute(status -> {
                Optional<IdempotencyStore.StoredResponse> replay = idempotency.replay(owner, key, requestHash);
                return replay.isPresent() ? replayed(replay.get())
                        : create(type, placement, stored, owner, key, requestHash);
            });
            if (outcome.replayed()) {
                contentWriter.discard(stored);
            }
            return outcome;
        } catch (DataIntegrityViolationException concurrentSameKey) {
            contentWriter.discard(stored);
            return transactions.execute(status -> idempotency.replay(owner, key, requestHash).map(this::replayed)
                    .orElseThrow(() -> concurrentSameKey));
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    private StartOutcome create(DocumentTypeInfo type, Placement placement, StoredContent stored, String owner,
            String key, String requestHash) {
        Instant expiresAt = Instant.now().plus(settings.ttl()).truncatedTo(ChronoUnit.MICROS);
        Capture capture = captures.save(new Capture(type.id(), placement.branchId(), placement.folderId(),
                placement.classification(), stored, expiresAt));
        captures.flush();
        jobs.enqueue(JobType.CAPTURE, new CaptureJobPayload(capture.getTenantId(), capture.getId()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentTypeCode", type.code());
        details.put("sizeBytes", stored.sizeBytes());
        details.put("sha256", stored.sha256());
        auditTrail.record(AuditEntry.success(STARTED, RESOURCE_CAPTURE, capture.getId(), details));
        JsonNode body = jsonMapper.valueToTree(new CaptureView(capture.getId(), capture.getStatus(), type.code(),
                false, List.of(), expiresAt));
        idempotency.remember(owner, key, requestHash, 202, body.toString());
        return new StartOutcome(202, capture.getId(), body, false);
    }

    private StartOutcome replayed(IdempotencyStore.StoredResponse stored) {
        JsonNode body = jsonMapper.readTree(stored.body());
        UUID id = UUID.fromString(body.get("id").asString());
        auditTrail.record(AuditEntry.success(DocumentAuditActions.REQUEST_REPLAYED, RESOURCE_CAPTURE, id,
                Map.of("status", stored.status())));
        return new StartOutcome(stored.status(), id, body, true);
    }

    /** The owner's capture with its suggestions; 404 for anyone else, after expiry, or without upload permission. */
    @Transactional(readOnly = true)
    public CaptureView view(UUID id) {
        Capture capture = owned(id);
        DocumentTypeInfo type = catalog.findDocumentType(capture.getDocumentTypeId()).orElseThrow(CaptureService::notFound);
        access.requireUpload(new ResourceRef.NewDocument(capture.getTenantId(), type.cabinetId(), type.id(),
                catalog.folderPath(capture.getFolderId()), capture.getBranchId(), capture.getClassification()));
        List<FieldSuggestion> suggestions = capture.getStatus() == CaptureStatus.DONE && capture.suggestions() != null
                ? readSuggestions(capture) : List.of();
        return new CaptureView(capture.getId(), capture.getStatus(), type.code(), capture.isOcr(), suggestions,
                capture.getExpiresAt());
    }

    /** The owner no longer needs it (file replaced, upload done or abandoned): file and suggestions are deleted. */
    @Transactional
    public void discard(UUID id) {
        Capture capture = owned(id);
        remove(capture);
        auditTrail.record(AuditEntry.success(DISCARDED, RESOURCE_CAPTURE, capture.getId(), Map.of()));
    }

    /** Applies the worker's result once; a repeated or late result changes nothing and its blob is deleted. */
    @Transactional
    public void apply(CaptureResult result) {
        Capture capture = captures.lockForUpdate(result.captureId()).orElse(null);
        String suggestionsKey = result.suggestions() == null ? null : result.suggestions().blobKey();
        if (capture == null || capture.getStatus() != CaptureStatus.PENDING) {
            if (suggestionsKey != null && (capture == null || capture.suggestions() == null
                    || !suggestionsKey.equals(capture.suggestions().blobKey()))) {
                deleteAfterCommit(List.of(suggestionsKey));
            }
            return; // discarded, expired, or a duplicate delivery
        }
        StoredContent file = capture.content();
        CaptureStatus status = switch (result.outcome()) {
            case DONE -> CaptureStatus.DONE;
            case INFECTED -> CaptureStatus.INFECTED;
            case FAILED -> CaptureStatus.FAILED;
        };
        RenditionBlob blob = result.outcome() == CaptureOutcome.DONE ? result.suggestions() : null;
        capture.finish(status, result.detectedMimeType(), result.ocr(), blob == null ? null
                : new StoredContent(blob.blobKey(), blob.sha256(), blob.sizeBytes(), blob.wrappedKey(), blob.keyRef()),
                result.suggestionCount());
        if (blob == null && suggestionsKey != null) {
            deleteAfterCommit(List.of(suggestionsKey));
        }
        if (file != null) {
            deleteAfterCommit(List.of(file.blobKey())); // read: the file is not kept
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", result.outcome().name());
        details.put("suggestionCount", capture.getSuggestionCount());
        details.put("ocr", result.ocr());
        if (result.detectedMimeType() != null) {
            details.put("detectedMimeType", result.detectedMimeType());
        }
        if (result.detail() != null) {
            details.put(result.outcome() == CaptureOutcome.INFECTED ? "signature" : "reason", result.detail());
        }
        String action = switch (status) {
            case INFECTED -> INFECTED;
            case FAILED -> FAILED;
            default -> READ;
        };
        auditTrail.record(AuditEntry.success(action, RESOURCE_CAPTURE, capture.getId(), details));
    }

    /** Removes the current tenant's expired captures, a batch at a time. Returns how many were removed. */
    @Transactional
    public int expire(Instant now) {
        List<Capture> expired = captures.findByExpiresAtBefore(now, Limit.of(EXPIRY_BATCH));
        expired.forEach(capture -> {
            remove(capture);
            auditTrail.record(AuditEntry.success(EXPIRED, RESOURCE_CAPTURE, capture.getId(),
                    Map.of("status", capture.getStatus().name())));
        });
        return expired.size();
    }

    /** Whether a capture refers to the blob (orphan reconciliation). */
    @Transactional(readOnly = true)
    public boolean referencesBlob(String blobKey) {
        return captures.referencesBlob(blobKey);
    }

    private void remove(Capture capture) {
        deleteAfterCommit(Stream.of(capture.content(), capture.suggestions()).filter(Objects::nonNull)
                .map(StoredContent::blobKey).toList());
        captures.delete(capture);
    }

    private Capture owned(UUID id) {
        RequestContext caller = RequestContext.require();
        return captures.findById(id)
                .filter(capture -> capture.getCreatedBy().equals(caller.subjectId().toString()))
                .filter(capture -> capture.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(CaptureService::notFound);
    }

    private List<FieldSuggestion> readSuggestions(Capture capture) {
        try (InputStream in = contentStore.open(capture.getTenantId(), capture.suggestions())) {
            byte[] json = in.readNBytes(MAX_SUGGESTIONS_BYTES);
            return jsonMapper.readValue(new String(json, StandardCharsets.UTF_8), SUGGESTIONS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ContentUnavailableException e) {
            log.warn("The suggestions of a capture could not be read; none are returned");
            return List.of();
        }
    }

    private record Placement(UUID branchId, UUID folderId, Classification classification) {

        ResourceRef.NewDocument resource(UUID tenantId, DocumentTypeInfo type, MetadataCatalog catalog) {
            return new ResourceRef.NewDocument(tenantId, type.cabinetId(), type.id(), catalog.folderPath(folderId),
                    branchId, classification);
        }
    }

    /** The intended branch, folder and classification, validated as the upload validates them. */
    private Placement placement(DocumentTypeInfo type, CaptureInput input, RequestContext caller) {
        List<Violation> violations = new ArrayList<>();
        UUID branchId = null;
        if (input.branchCode() == null || input.branchCode().isBlank()) {
            violations.add(new Violation("branchCode", "BRANCH_REQUIRED", "A branch is required."));
        } else {
            branchId = transactions.execute(status -> branches.findByCodes(List.of(input.branchCode())).stream()
                    .map(BranchInfo::id).findFirst().orElse(null));
            if (branchId == null) {
                violations.add(new Violation("branchCode", "BRANCH_UNKNOWN", "Unknown branch."));
            }
        }
        if (input.folderId() != null && !Boolean.TRUE.equals(transactions.execute(status ->
                catalog.folderInCabinet(input.folderId(), type.cabinetId())))) {
            violations.add(new Violation("folderId", "FOLDER_INVALID", "The folder does not exist in the cabinet."));
        }
        Classification classification = Optional.ofNullable(input.classification())
                .orElse(type.defaultClassification());
        if (!classification.covers(type.defaultClassification())) {
            violations.add(new Violation("classification", "CLASSIFICATION_BELOW_DEFAULT",
                    "Classification cannot be lower than the type's default (" + type.defaultClassification() + ")."));
        }
        if (!caller.clearance().covers(classification)) {
            violations.add(new Violation("classification", "CLASSIFICATION_ABOVE_CLEARANCE",
                    "You cannot classify a document above your own clearance."));
        }
        ValidationFailedException.throwIfAny("The capture request is invalid.", violations);
        return new Placement(branchId, input.folderId(), classification);
    }

    private void deleteAfterCommit(List<String> blobKeys) {
        if (blobKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                blobKeys.forEach(key -> {
                    try {
                        contentStore.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete a capture blob; the reconciler will find it");
                    }
                });
            }
        });
    }

    static ApiException notFound() {
        return ApiException.notFound(CAPTURE_NOT_FOUND, "Capture not found.");
    }

    private static String hash(String... parts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
