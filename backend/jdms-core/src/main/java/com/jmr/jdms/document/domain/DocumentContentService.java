package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Download of a version's original content (spec §4.5): decrypted while streaming, every segment authenticated and
 * the SHA-256 verified. Corrupt or missing content marks the version {@code MISSING} and raises an alert.
 */
@Service
public class DocumentContentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentContentService.class);

    public static final String CONTENT_UNAVAILABLE = "CONTENT_UNAVAILABLE";
    public static final String STORAGE_INTEGRITY_FAILURE = "STORAGE_INTEGRITY_FAILURE";
    public static final String DOCUMENT_QUARANTINED = "DOCUMENT_QUARANTINED";
    public static final String PREVIEW_UNAVAILABLE = "PREVIEW_UNAVAILABLE";
    public static final String THUMBNAIL_UNAVAILABLE = "THUMBNAIL_UNAVAILABLE";
    /** Originals shown inline when the worker detected them as such (never SVG: it can carry script). */
    static final Set<String> INLINE_ORIGINALS = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif",
            "image/webp");

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentAccess access;
    private final ContentStore contentStore;
    private final StorageIntegrityReporter integrity;
    private final AuditTrail auditTrail;
    private final TransactionTemplate transactions;
    private final RenditionRepository renditions;

    DocumentContentService(DocumentRepository documents, DocumentVersionRepository versions, DocumentAccess access,
            ContentStore contentStore, StorageIntegrityReporter integrity, AuditTrail auditTrail,
            TransactionTemplate transactions, RenditionRepository renditions) {
        this.documents = documents;
        this.versions = versions;
        this.access = access;
        this.contentStore = contentStore;
        this.integrity = integrity;
        this.auditTrail = auditTrail;
        this.transactions = transactions;
        this.renditions = renditions;
    }

    /** Descriptive data of a download; {@code content} is open and already verified to start correctly. */
    public record Download(String fileName, String mimeType, long sizeBytes, VerifiedContent content) {
    }

    public Download open(UUID documentId, UUID versionId) {
        RequestContext caller = RequestContext.require();
        DocumentVersion version = transactions.execute(status -> {
            Document document = access.require(Action.DOWNLOAD, documents.findById(documentId).orElse(null));
            if (document.getStatus() == DocumentStatus.DISPOSED) {
                throw new ApiException(HttpStatus.GONE, CONTENT_UNAVAILABLE, "The document was disposed.");
            }
            DocumentVersion found = versions.findByIdAndDocumentId(versionId, documentId)
                    .orElseThrow(() -> ApiException.notFound(DocumentVersioningService.VERSION_NOT_FOUND,
                            "Version not found."));
            boolean quarantined = found.getStorageStatus() == StorageStatus.QUARANTINED
                    || found.getProcessingStatus() == ProcessingStatus.QUARANTINED;
            if (quarantined && !caller.hasRole(Roles.SECURITY_ADMIN)) {
                throw ApiException.forbidden(DOCUMENT_QUARANTINED, "The file failed the antivirus scan.");
            }
            if (found.getStorageStatus() == StorageStatus.MISSING) {
                throw new ApiException(HttpStatus.CONFLICT, CONTENT_UNAVAILABLE,
                        "The content of this version is unavailable.");
            }
            if (found.getStorageStatus() == StorageStatus.DESTROYED) {
                throw new ApiException(HttpStatus.GONE, CONTENT_UNAVAILABLE, "The content was destroyed.");
            }
            auditTrail.record(AuditEntry.success(DocumentAuditActions.DOWNLOADED,
                    DocumentAuditActions.RESOURCE_DOCUMENT, documentId, Map.of("versionId", versionId.toString(),
                            "version", found.number())));
            return found;
        });

        Runnable reportFailure = () -> TenantScope.run(caller.tenantId(),
                () -> integrity.report(documentId, versionId, "Content failed decryption or SHA-256 check"));
        try {
            InputStream decrypted = contentStore.open(caller.tenantId(), version.content());
            VerifiedContent verified = new VerifiedContent(decrypted, version.getSha256(), reportFailure);
            return new Download(version.getOriginalFilename(), version.getMimeType(), version.getSizeBytes(),
                    verified);
        } catch (ContentUnavailableException | IOException e) {
            reportFailure.run();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, STORAGE_INTEGRITY_FAILURE,
                    "The stored content failed its integrity check. Operations have been alerted.");
        }
    }

    /** Content to show inline: its type is set by the pipeline, never by the uploader. */
    public record Inline(String mimeType, long sizeBytes, VerifiedContent content) {
    }

    /**
     * The preview (spec §4.5): the PDF rendition; or the original when the worker detected it as a PDF or a common
     * image type. 409 {@code PREVIEW_UNAVAILABLE} otherwise (not processed yet, or no rendition possible).
     */
    public Inline preview(UUID documentId, UUID versionId) {
        return inline(documentId, versionId, "DOCUMENT_PREVIEWED", version -> {
            Optional<Rendition> pdf = renditions.findByVersionIdAndKind(version.getId(), Rendition.PDF);
            if (pdf.isPresent()) {
                return new Shown(pdf.get().getMimeType(), pdf.get().content());
            }
            if (version.getDetectedMimeType() != null && INLINE_ORIGINALS.contains(version.getDetectedMimeType())) {
                return new Shown(version.getDetectedMimeType(), version.content());
            }
            throw new ApiException(HttpStatus.CONFLICT, PREVIEW_UNAVAILABLE, "No preview is available (yet).");
        });
    }

    /** The PNG thumbnail of the first page; 409 {@code THUMBNAIL_UNAVAILABLE} when there is none (yet). */
    public Inline thumbnail(UUID documentId, UUID versionId) {
        return inline(documentId, versionId, "DOCUMENT_THUMBNAIL_VIEWED", version -> renditions
                .findByVersionIdAndKind(version.getId(), Rendition.THUMBNAIL)
                .map(rendition -> new Shown(rendition.getMimeType(), rendition.content()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, THUMBNAIL_UNAVAILABLE,
                        "No thumbnail is available (yet).")));
    }

    private record Shown(String mimeType, StoredContent content) {
    }

    private Inline inline(UUID documentId, UUID versionId, String auditAction,
            Function<DocumentVersion, Shown> choose) {
        RequestContext caller = RequestContext.require();
        Shown shown = transactions.execute(status -> {
            Document document = access.require(Action.VIEW_CONTENT, documents.findById(documentId).orElse(null));
            if (document.getStatus() == DocumentStatus.DISPOSED) {
                throw new ApiException(HttpStatus.GONE, CONTENT_UNAVAILABLE, "The document was disposed.");
            }
            DocumentVersion version = versions.findByIdAndDocumentId(versionId, documentId)
                    .orElseThrow(() -> ApiException.notFound(DocumentVersioningService.VERSION_NOT_FOUND,
                            "Version not found."));
            if (version.getStorageStatus() == StorageStatus.QUARANTINED
                    || version.getProcessingStatus() == ProcessingStatus.QUARANTINED) {
                throw ApiException.forbidden(DOCUMENT_QUARANTINED, "The file failed the antivirus scan.");
            }
            Shown chosen = choose.apply(version);
            auditTrail.record(AuditEntry.success(auditAction, DocumentAuditActions.RESOURCE_DOCUMENT, documentId,
                    Map.of("versionId", versionId.toString(), "version", version.number())));
            return chosen;
        });
        Runnable reportFailure = () -> log.warn("Rendition or preview of document {} version {} failed its integrity "
                + "check", documentId, versionId);
        try {
            return new Inline(shown.mimeType(), shown.content().sizeBytes(), new VerifiedContent(
                    contentStore.open(caller.tenantId(), shown.content()), shown.content().sha256(), reportFailure));
        } catch (ContentUnavailableException | IOException e) {
            reportFailure.run();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, STORAGE_INTEGRITY_FAILURE,
                    "The stored content failed its integrity check. Operations have been alerted.");
        }
    }
}
