package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.VersionCreated;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.idempotency.IdempotencyStore;
import com.jmr.jdms.workflow.api.ApprovalRequest;
import com.jmr.jdms.workflow.api.Approvals;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Check-out, drafts, check-in, cancel and restore (spec §4.3). Versions are never changed: drafts are minor versions
 * under the latest major; check-in and restore create the next major version. When the type needs approval the new
 * major version is {@code PENDING} and the previous approved version stays current until approval (B-05).
 */
@Service
public class DocumentVersioningService {

    public static final String DOCUMENT_CHECKED_OUT = "DOCUMENT_CHECKED_OUT";
    public static final String NOT_CHECKED_OUT_BY_YOU = "NOT_CHECKED_OUT_BY_YOU";
    public static final String DOCUMENT_NOT_ACTIVE = "DOCUMENT_NOT_ACTIVE";
    public static final String VERSION_PENDING_APPROVAL = "VERSION_PENDING_APPROVAL";
    public static final String NOTHING_TO_CHECK_IN = "NOTHING_TO_CHECK_IN";
    public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
    public static final String VERSION_NOT_RESTORABLE = "VERSION_NOT_RESTORABLE";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentAccess access;
    private final DocumentViews views;
    private final MetadataCatalog catalog;
    private final ContentWriter contentWriter;
    private final ContentStore contentStore;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Approvals approvals;
    private final IdempotencyStore idempotency;
    private final JsonMapper jsonMapper;
    private final CabinetUsages usages;

    DocumentVersioningService(DocumentRepository documents, DocumentVersionRepository versions, DocumentAccess access,
            DocumentViews views, MetadataCatalog catalog, ContentWriter contentWriter, ContentStore contentStore,
            AuditTrail auditTrail, ApplicationEventPublisher events, TransactionTemplate transactions,
            Approvals approvals, IdempotencyStore idempotency, JsonMapper jsonMapper, CabinetUsages usages) {
        this.documents = documents;
        this.versions = versions;
        this.access = access;
        this.views = views;
        this.catalog = catalog;
        this.contentWriter = contentWriter;
        this.contentStore = contentStore;
        this.auditTrail = auditTrail;
        this.events = events;
        this.transactions = transactions;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.jsonMapper = jsonMapper;
        this.usages = usages;
    }

    public DocumentView checkOut(UUID documentId) {
        return transactions.execute(status -> {
            Document document = load(documentId);
            UUID caller = RequestContext.require().subjectId();
            if (document.isCheckedOutBy(caller)) {
                audit(DocumentAuditActions.CHECKED_OUT, document, Map.of("repeated", true));
                return views.view(document);
            }
            requireWritable(document);
            if (document.isCheckedOut()) {
                throw conflict(DOCUMENT_CHECKED_OUT, "The document is checked out by another user.");
            }
            if (hasPendingVersion(documentId)) {
                throw conflict(VERSION_PENDING_APPROVAL, "A version of this document is waiting for approval.");
            }
            document.checkOut(caller, now());
            documents.flush();
            audit(DocumentAuditActions.CHECKED_OUT, document, Map.of());
            return views.view(document);
        });
    }

    /** Discards the drafts of this check-out (rows now, blobs after commit) and releases the lock. */
    public DocumentView cancelCheckOut(UUID documentId) {
        return transactions.execute(status -> {
            Document document = loadCheckedOutByCaller(documentId);
            List<DocumentVersion> drafts = currentDrafts(documentId);
            versions.deleteAll(drafts);
            usages.release(document.getCabinetId(), drafts.stream().mapToLong(DocumentVersion::getSizeBytes).sum());
            document.releaseCheckOut();
            documents.flush();
            afterCommit(() -> drafts.forEach(draft -> contentWriter.discard(draft.content())));
            audit(DocumentAuditActions.CHECKOUT_CANCELLED, document,
                    Map.of("discardedDrafts", drafts.stream().map(DocumentVersion::number).toList()));
            return views.view(document);
        });
    }

    /** A draft (minor) version while checked out by the caller. */
    public VersionView uploadDraft(UUID documentId, InputStream content, String fileName, String mimeType,
            String comment, String idempotencyKey) {
        IdempotencyStore.requireKey(idempotencyKey);
        DocumentTypeInfo type = transactions.execute(status -> typeOf(loadCheckedOutByCaller(documentId)));
        StoredContent stored = contentWriter.store(content, type.maxFileSizeMb());
        try {
            return transactions.execute(status -> {
                Document document = loadCheckedOutByCaller(documentId);
                DocumentVersion latest = latest(documentId);
                DocumentVersion draft = versions.save(new DocumentVersion(null, documentId, latest.getMajor(),
                        nextMinor(documentId, latest.getMajor()), ApprovalStatus.DRAFT, stored,
                        ClientFileInfo.mimeType(mimeType), ClientFileInfo.fileName(fileName), comment(comment)));
                usages.add(document.getCabinetId(), stored.sizeBytes());
                versions.flush();
                audit(DocumentAuditActions.DRAFT_UPLOADED, document, Map.of("version", draft.number(),
                        "versionId", draft.getId().toString()));
                events.publishEvent(new VersionCreated(RequestContext.require().tenantId(), documentId, draft.getId()));
                return VersionView.of(draft);
            });
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    /**
     * Creates the next major version from the given content, or from the latest draft when no content is given, and
     * releases the lock.
     */
    public DocumentView checkIn(UUID documentId, InputStream content, String fileName, String mimeType,
            String comment) {
        DocumentTypeInfo type = transactions.execute(status -> typeOf(loadCheckedOutByCaller(documentId)));
        StoredContent stored;
        String name;
        String mime;
        if (content != null) {
            stored = contentWriter.store(content, type.maxFileSizeMb());
            name = ClientFileInfo.fileName(fileName);
            mime = ClientFileInfo.mimeType(mimeType);
        } else {
            DocumentVersion draft = transactions.execute(status -> currentDrafts(documentId).stream()
                    .max(Comparator.comparingInt(DocumentVersion::getMinor)).orElse(null));
            if (draft == null) {
                throw ApiException.unprocessable(NOTHING_TO_CHECK_IN, "Upload a file or a draft version first.");
            }
            stored = copy(draft);
            name = draft.getOriginalFilename();
            mime = draft.getMimeType();
        }
        try {
            return transactions.execute(status -> {
                Document document = loadCheckedOutByCaller(documentId);
                DocumentVersion created = createMajor(document, type, stored, mime, name, comment(comment));
                document.releaseCheckOut();
                documents.flush();
                audit(DocumentAuditActions.CHECKED_IN, document, Map.of("version", created.number(),
                        "versionId", created.getId().toString(), "approvalStatus", created.getApprovalStatus().name()));
                return views.view(document);
            });
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    /**
     * A new major version in one call, for integration clients (no explicit check-out): allowed when nobody else has
     * the document checked out. Idempotent: the same key and request return the original response; the same key with
     * another request is 409 {@code IDEMPOTENCY_KEY_REUSED}.
     */
    public UploadOutcome newVersion(UUID documentId, InputStream content, String fileName, String mimeType,
            String comment, String idempotencyKey) {
        String key = IdempotencyStore.requireKey(idempotencyKey);
        String clientId = RequestContext.require().subjectId().toString();
        DocumentTypeInfo type = transactions.execute(status -> typeOf(load(documentId)));
        StoredContent stored = contentWriter.store(content, type.maxFileSizeMb());
        String safeName = ClientFileInfo.fileName(fileName);
        String safeMime = ClientFileInfo.mimeType(mimeType);
        String requestHash = sha256(documentId + "|" + stored.sha256() + "|" + safeName + "|" + safeMime + "|"
                + comment(comment));
        try {
            UploadOutcome outcome = transactions.execute(status -> {
                Optional<IdempotencyStore.StoredResponse> replay = idempotency.replay(clientId, key, requestHash);
                if (replay.isPresent()) {
                    return replayed(documentId, replay.get());
                }
                Document document = load(documentId);
                requireWritable(document);
                if (document.isCheckedOut() && !document.isCheckedOutBy(RequestContext.require().subjectId())) {
                    throw conflict(DOCUMENT_CHECKED_OUT, "The document is checked out by another user.");
                }
                DocumentVersion created = createMajor(document, type, stored, safeMime, safeName, comment(comment));
                document.releaseCheckOut();
                documents.flush();
                audit(DocumentAuditActions.CHECKED_IN, document, Map.of("version", created.number(),
                        "versionId", created.getId().toString(), "approvalStatus", created.getApprovalStatus().name()));
                JsonNode body = jsonMapper.valueToTree(VersionView.of(created));
                idempotency.remember(clientId, key, requestHash, 201, body.toString());
                return new UploadOutcome(201, documentId, body, false);
            });
            if (outcome.replayed()) {
                contentWriter.discard(stored);
            }
            return outcome;
        } catch (DataIntegrityViolationException concurrentSameKey) {
            contentWriter.discard(stored);
            return transactions.execute(status -> idempotency.replay(clientId, key, requestHash)
                    .map(replay -> replayed(documentId, replay)).orElseThrow(() -> concurrentSameKey));
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Restoring never rewrites history: the old content becomes a new major version. */
    public DocumentView restore(UUID documentId, UUID versionId) {
        DocumentVersion source = transactions.execute(status -> {
            Document document = load(documentId);
            requireWritable(document);
            if (document.isCheckedOut()) {
                throw conflict(DOCUMENT_CHECKED_OUT, "The document is checked out.");
            }
            DocumentVersion version = versions.findByIdAndDocumentId(versionId, documentId)
                    .orElseThrow(() -> ApiException.notFound(VERSION_NOT_FOUND, "Version not found."));
            if (version.getApprovalStatus() != ApprovalStatus.APPROVED) {
                throw ApiException.unprocessable(VERSION_NOT_RESTORABLE, "Only approved versions can be restored.");
            }
            return version;
        });
        DocumentTypeInfo type = transactions.execute(status -> typeOf(load(documentId)));
        StoredContent stored = copy(source);
        try {
            return transactions.execute(status -> {
                Document document = load(documentId);
                requireWritable(document);
                if (document.isCheckedOut()) {
                    throw conflict(DOCUMENT_CHECKED_OUT, "The document is checked out.");
                }
                DocumentVersion created = createMajor(document, type, stored, source.getMimeType(),
                        source.getOriginalFilename(), "Restored from version " + source.number());
                documents.flush();
                audit(DocumentAuditActions.VERSION_RESTORED, document, Map.of("from", source.number(),
                        "version", created.number(), "versionId", created.getId().toString()));
                return views.view(document);
            });
        } catch (RuntimeException e) {
            contentWriter.discard(stored);
            throw e;
        }
    }

    private DocumentVersion createMajor(Document document, DocumentTypeInfo type, StoredContent stored, String mime,
            String name, String comment) {
        if (hasPendingVersion(document.getId())) {
            throw conflict(VERSION_PENDING_APPROVAL, "A version of this document is waiting for approval.");
        }
        boolean approval = type.requiresApproval();
        DocumentVersion created = versions.save(new DocumentVersion(null, document.getId(),
                latest(document.getId()).getMajor() + 1, 0, approval ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED,
                stored, mime, name, comment));
        usages.add(document.getCabinetId(), stored.sizeBytes());
        if (!approval) {
            document.makeCurrent(created.getId());
        }
        events.publishEvent(new VersionCreated(RequestContext.require().tenantId(), document.getId(), created.getId()));
        if (approval) {
            versions.flush();
            approvals.start(new ApprovalRequest(type.processKey(), DocumentApprovals.VERSION, created.getId(),
                    document.getId(), created.getId(), "Version " + created.number() + ": " + document.getTitle()
                            + " (" + type.code() + ")"));
        }
        return created;
    }

    /** Re-encrypts a version's content under a new data key and blob (versions never share blobs). */
    private StoredContent copy(DocumentVersion source) {
        RequestContext caller = RequestContext.require();
        try (InputStream plaintext = contentStore.open(caller.tenantId(), source.content())) {
            return contentStore.store(caller.tenantId(), caller.tenantCode(), plaintext, Long.MAX_VALUE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy content", e);
        }
    }

    /** Every versioning operation needs {@code CHECK_OUT} on the document (blocked by holds and declared records). */
    private Document load(UUID documentId) {
        return access.require(Action.CHECK_OUT, documents.findById(documentId).orElse(null));
    }

    private Document loadCheckedOutByCaller(UUID documentId) {
        Document document = load(documentId);
        requireWritable(document);
        if (!document.isCheckedOutBy(RequestContext.require().subjectId())) {
            throw conflict(NOT_CHECKED_OUT_BY_YOU, "Check the document out first.");
        }
        return document;
    }

    private static void requireWritable(Document document) {
        if (document.getStatus() != DocumentStatus.ACTIVE) {
            throw conflict(DOCUMENT_NOT_ACTIVE, "Only active documents can be versioned.");
        }
    }

    private DocumentTypeInfo typeOf(Document document) {
        return catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
    }

    private DocumentVersion latest(UUID documentId) {
        return versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId).stream()
                .filter(version -> version.getMinor() == 0).findFirst().orElseThrow();
    }

    private int nextMinor(UUID documentId, int major) {
        return versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId).stream()
                .filter(version -> version.getMajor() == major).mapToInt(DocumentVersion::getMinor).max().orElse(0) + 1;
    }

    private List<DocumentVersion> currentDrafts(UUID documentId) {
        int major = latest(documentId).getMajor();
        return versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId).stream()
                .filter(version -> version.getMajor() == major && version.getApprovalStatus() == ApprovalStatus.DRAFT)
                .toList();
    }

    private boolean hasPendingVersion(UUID documentId) {
        return versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId).stream()
                .anyMatch(version -> version.getApprovalStatus() == ApprovalStatus.PENDING);
    }

    private UploadOutcome replayed(UUID documentId, IdempotencyStore.StoredResponse replay) {
        auditTrail.record(AuditEntry.success(DocumentAuditActions.REQUEST_REPLAYED,
                DocumentAuditActions.RESOURCE_DOCUMENT, documentId, Map.of("status", replay.status())));
        return new UploadOutcome(replay.status(), documentId, jsonMapper.readTree(replay.body()), true);
    }

    private void audit(String action, Document document, Map<String, Object> details) {
        auditTrail.record(AuditEntry.success(action, DocumentAuditActions.RESOURCE_DOCUMENT, document.getId(),
                details));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String comment(String comment) {
        return Optional.ofNullable(comment).map(String::trim).filter(text -> !text.isEmpty())
                .map(text -> text.length() > 1000 ? text.substring(0, 1000) : text).orElse(null);
    }

    private static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
