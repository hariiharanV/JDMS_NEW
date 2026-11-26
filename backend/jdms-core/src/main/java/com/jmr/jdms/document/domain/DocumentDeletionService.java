package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentChanged;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft delete and restore (spec §4.7): deleting moves a document to the recycle bin ({@code DELETED}), after a
 * checker's approval when its type requires it (maker-checker, ADR-0009); restoring returns it to its previous status.
 * Documents on legal hold or declared as records cannot be deleted; permanent removal is disposition's job (B-10).
 */
@Service
public class DocumentDeletionService implements ChangeHandler {

    public static final String SUBJECT = "DOCUMENT_DELETE";
    public static final String DOC_LEGAL_HOLD = "DOC_LEGAL_HOLD";
    public static final String DOC_DECLARED_RECORD = "DOC_DECLARED_RECORD";
    public static final String DOCUMENT_NOT_DELETED = "DOCUMENT_NOT_DELETED";
    static final Set<DocumentStatus> DELETABLE = Set.of(DocumentStatus.ACTIVE, DocumentStatus.REJECTED,
            DocumentStatus.DRAFT);

    private final DocumentRepository documents;
    private final DocumentAccess access;
    private final DocumentViews views;
    private final MetadataCatalog catalog;
    private final ChangeRequests changeRequests;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;

    DocumentDeletionService(DocumentRepository documents, DocumentAccess access, DocumentViews views,
            MetadataCatalog catalog, ChangeRequests changeRequests, AuditTrail auditTrail,
            ApplicationEventPublisher events) {
        this.documents = documents;
        this.access = access;
        this.views = views;
        this.catalog = catalog;
        this.changeRequests = changeRequests;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    /** Deleted document (in the recycle bin), or a pending change when the type requires approval. */
    public record DeleteOutcome(DocumentView document, PendingChangeView pendingChange) {
    }

    @Transactional
    public DeleteOutcome delete(UUID documentId) {
        Document document = access.requireVisible(documents.findById(documentId).orElse(null));
        blocker(document).ifPresent(blocked -> {
            throw blocked;
        });
        access.require(Action.DELETE, document);
        DocumentTypeInfo type = catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
        if (type.deleteRequiresApproval()) {
            return new DeleteOutcome(null, changeRequests.submit(new ChangeProposal(SUBJECT,
                    ChangeOperation.DELETE, documentId, null, document.getVersionLock(), Map.of(),
                    "Delete document \"" + document.getTitle() + "\"")));
        }
        softDelete(document, null);
        return new DeleteOutcome(views.view(document), null);
    }

    @Transactional
    public DocumentView restore(UUID documentId) {
        Document document = access.require(Action.RESTORE, documents.findById(documentId).orElse(null));
        if (document.getStatus() != DocumentStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, DOCUMENT_NOT_DELETED, "The document is not in the recycle bin.");
        }
        document.restoreFromRecycleBin();
        documents.flush();
        audit("DOCUMENT_RESTORED", document, Map.of("status", document.getStatus().name()));
        events.publishEvent(new DocumentChanged(document.getTenantId(), document.getId()));
        return views.view(document);
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.CHECKER);
    }

    @Override
    public String historyType() {
        return "DELETE";
    }

    @Override
    public UUID documentId(PendingChangeView change) {
        return change.subjectId();
    }

    /** Checkers approve deletions of documents they may approve. */
    @Override
    public boolean canDecide(PendingChangeView change) {
        return documents.findById(change.subjectId()).map(document -> access.can(Action.APPROVE, document))
                .orElse(false);
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        Document document = documents.findById(change.subjectId()).orElse(null);
        if (document == null || document.getVersionLock() != change.baseVersion()) {
            return ApplyResult.stale("The document was changed after the deletion was proposed.");
        }
        Optional<ApiException> blocked = blocker(document);
        if (blocked.isPresent()) {
            return ApplyResult.stale(blocked.get().getMessage());
        }
        softDelete(document, change);
        return ApplyResult.ok();
    }

    private Optional<ApiException> blocker(Document document) {
        if (document.isOnLegalHold()) {
            return Optional.of(new ApiException(HttpStatus.CONFLICT, DOC_LEGAL_HOLD,
                    "The document is under legal hold and cannot be deleted."));
        }
        if (document.getRecordDeclaredAt() != null) {
            return Optional.of(new ApiException(HttpStatus.CONFLICT, DOC_DECLARED_RECORD,
                    "The document is a declared record; only disposition can remove it."));
        }
        if (!DELETABLE.contains(document.getStatus())) {
            return Optional.of(new ApiException(HttpStatus.CONFLICT, DocumentVersioningService.DOCUMENT_NOT_ACTIVE,
                    "A " + document.getStatus().name().toLowerCase().replace('_', ' ')
                            + " document cannot be deleted."));
        }
        if (document.isCheckedOut()) {
            return Optional.of(new ApiException(HttpStatus.CONFLICT, DocumentVersioningService.DOCUMENT_CHECKED_OUT,
                    "The document is checked out."));
        }
        return Optional.empty();
    }

    private void softDelete(Document document, PendingChangeView change) {
        document.moveToRecycleBin(RequestContext.require().subjectId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        documents.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousStatus", document.getStatusBeforeDelete().name());
        if (change != null) {
            details.put("changeId", change.id().toString());
            details.put("proposedBy", change.proposedBy().toString());
        }
        audit("DOCUMENT_DELETED", document, details);
        events.publishEvent(new DocumentChanged(document.getTenantId(), document.getId()));
    }

    private void audit(String action, Document document, Map<String, Object> details) {
        auditTrail.record(AuditEntry.success(action, DocumentAuditActions.RESOURCE_DOCUMENT, document.getId(),
                details));
    }
}
