package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentCreated;
import com.jmr.jdms.contracts.VersionCreated;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.idempotency.IdempotencyStore;
import com.jmr.jdms.workflow.api.ApprovalRequest;
import com.jmr.jdms.workflow.api.Approvals;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Upload (spec §4.2): validate metadata, store the encrypted blob, then in one transaction create the document and
 * version 1.0, audit, publish {@link DocumentCreated} and {@link VersionCreated} through the outbox, and remember the
 * response for the {@code Idempotency-Key}. Blob first, metadata second: if the transaction fails the blob is
 * deleted (or found later by the reconciliation job).
 */
@Service
public class DocumentUploadService {

    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String DUPLICATE_CONTENT = "DUPLICATE_CONTENT";

    private static final long MB = 1024L * 1024L;
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final MetadataResolver resolver;
    private final ContentWriter contentWriter;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentViews views;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final IdempotencyStore idempotency;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final DocumentAccess access;
    private final MetadataCatalog catalog;
    private final Approvals approvals;
    private final CabinetUsages usages;

    DocumentUploadService(MetadataResolver resolver, ContentWriter contentWriter, DocumentRepository documents,
            DocumentVersionRepository versions, DocumentViews views, AuditTrail auditTrail,
            ApplicationEventPublisher events, IdempotencyStore idempotency, TransactionTemplate transactions,
            JsonMapper jsonMapper, DocumentAccess access, MetadataCatalog catalog, Approvals approvals,
            CabinetUsages usages) {
        this.resolver = resolver;
        this.contentWriter = contentWriter;
        this.documents = documents;
        this.versions = versions;
        this.views = views;
        this.auditTrail = auditTrail;
        this.events = events;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.access = access;
        this.catalog = catalog;
        this.approvals = approvals;
        this.usages = usages;
    }

    /** Metadata is validated before the content stream is read; nothing is stored for invalid metadata. */
    public UploadOutcome upload(DocumentFieldsInput input, String fileName, String mimeType, InputStream content,
            String idempotencyKey) {
        return upload(input, fileName, mimeType, content, idempotencyKey, null);
    }

    /** @param externalSystem the bank system an integration client uploads for (recorded as the source) */
    public UploadOutcome upload(DocumentFieldsInput input, String fileName, String mimeType, InputStream content,
            String idempotencyKey, String externalSystem) {
        String key = IdempotencyStore.requireKey(idempotencyKey);
        RequestContext caller = RequestContext.require();
        DocumentTypeInfo type = resolver.uploadableType(input);
        String safeName = ClientFileInfo.fileName(fileName);
        String safeMime = ClientFileInfo.mimeType(mimeType);
        DocumentMetadata metadata = resolver.resolve(type, input, safeName);
        access.requireUpload(new ResourceRef.NewDocument(caller.tenantId(), type.cabinetId(), type.id(),
                catalog.folderPath(metadata.folderId()), metadata.branchId(), metadata.classification()));

        StoredContent stored = contentWriter.store(content, type.maxFileSizeMb());
        String requestHash = requestHash(input, stored.sha256(), safeName,
                externalSystem == null ? safeMime : safeMime + "|" + externalSystem);
        String clientId = caller.subjectId().toString();
        try {
            UploadOutcome outcome = transactions.execute(status -> {
                Optional<IdempotencyStore.StoredResponse> replay = idempotency.replay(clientId, key, requestHash);
                if (replay.isPresent()) {
                    return replayed(replay.get());
                }
                return create(type, metadata, stored, safeName, safeMime, caller, clientId, key, requestHash,
                        externalSystem);
            });
            if (outcome.replayed()) {
                contentWriter.discard(stored);
            }
            return outcome;
        } catch (DataIntegrityViolationException concurrentSameKey) {
            contentWriter.discard(stored);
            return transactions.execute(status -> idempotency.replay(clientId, key, requestHash)
                    .map(this::replayed).orElseThrow(() -> concurrentSameKey));
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    private UploadOutcome create(DocumentTypeInfo type, DocumentMetadata metadata, StoredContent stored,
            String fileName, String mimeType, RequestContext caller, String clientId, String key, String requestHash,
            String externalSystem) {
        if (!usages.reserve(type.cabinetId(), stored.sizeBytes(), type.quotaMb() * MB)) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, QUOTA_EXCEEDED,
                    "The cabinet's storage quota would be exceeded.");
        }
        long duplicates = metadata.standard().cif() == null ? 0
                : documents.countWithSameContent(type.cabinetId(), metadata.standard().cif(), stored.sha256());

        boolean approval = type.requiresApproval();
        Document document = documents.save(new Document(type.cabinetId(), type.id(),
                approval ? DocumentStatus.PENDING_APPROVAL : DocumentStatus.ACTIVE, sourceSystem(caller, externalSystem),
                metadata));
        DocumentVersion version = versions.save(new DocumentVersion(null, document.getId(), 1, 0,
                approval ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED, stored, mimeType, fileName, null));
        document.makeCurrent(version.getId());
        documents.flush();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentTypeCode", type.code());
        details.put("versionId", version.getId().toString());
        details.put("version", version.number());
        details.put("sizeBytes", stored.sizeBytes());
        details.put("sha256", stored.sha256());
        auditTrail.record(AuditEntry.success(DocumentAuditActions.UPLOADED, DocumentAuditActions.RESOURCE_DOCUMENT,
                document.getId(), details));
        events.publishEvent(new DocumentCreated(caller.tenantId(), document.getId(), version.getId()));
        events.publishEvent(new VersionCreated(caller.tenantId(), document.getId(), version.getId()));
        if (approval) {
            // in this transaction: the document never exists without its approval process (spec §4.4)
            approvals.start(new ApprovalRequest(type.processKey(), DocumentApprovals.DOCUMENT, document.getId(),
                    document.getId(), version.getId(), "Upload: " + document.getTitle() + " (" + type.code() + ")"));
        }

        ObjectNode body = jsonMapper.valueToTree(views.view(document, version));
        // duplicates are counted, not listed: listing IDs could reveal documents the caller may not see (B-04)
        body.set("warnings", jsonMapper.valueToTree(duplicates == 0 ? List.of() : List.of(Map.of(
                "code", DUPLICATE_CONTENT,
                "message", "The same file is already stored for this customer in this cabinet.",
                "count", duplicates))));
        idempotency.remember(clientId, key, requestHash, 201, body.toString());
        return new UploadOutcome(201, document.getId(), body, false);
    }

    private UploadOutcome replayed(IdempotencyStore.StoredResponse stored) {
        JsonNode body = jsonMapper.readTree(stored.body());
        UUID documentId = UUID.fromString(body.get("id").asString());
        auditTrail.record(AuditEntry.success(DocumentAuditActions.REQUEST_REPLAYED,
                DocumentAuditActions.RESOURCE_DOCUMENT, documentId, Map.of("status", stored.status())));
        return new UploadOutcome(stored.status(), documentId, body, true);
    }

    static String sourceSystem(RequestContext caller, String externalSystem) {
        if (caller.principalType() == PrincipalType.CLIENT) {
            String source = "INTEGRATION:" + caller.username().replaceFirst("^service-account-", "")
                    + (externalSystem == null ? "" : ":" + externalSystem);
            return source.length() > 100 ? source.substring(0, 100) : source;
        }
        return "UI";
    }

    private static String requestHash(DocumentFieldsInput input, String contentSha256, String fileName,
            String mimeType) {
        String canonical = CANONICAL.writeValueAsString(input) + "|" + contentSha256 + "|" + fileName + "|"
                + mimeType;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
