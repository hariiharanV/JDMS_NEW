package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import org.springframework.stereotype.Component;

/**
 * Document authorization through the access module's policy (spec §6). A document the caller may not see
 * ({@code VIEW_METADATA}) is reported as not found, so its existence is not revealed; a visible document with a
 * forbidden action is 403. Denials are audited by the policy.
 */
@Component
public class DocumentAccess {

    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

    private final AccessPolicy policy;
    private final MetadataCatalog catalog;

    DocumentAccess(AccessPolicy policy, MetadataCatalog catalog) {
        this.policy = policy;
        this.catalog = catalog;
    }

    public ResourceRef.Document resourceOf(Document document) {
        return new ResourceRef.Document(document.getTenantId(), document.getId(), document.getCabinetId(),
                document.getDocumentTypeId(), catalog.folderPath(document.getFolderId()), document.getBranchId(),
                document.getClassification(), document.isOnLegalHold(), document.getRecordDeclaredAt() != null);
    }

    public Document requireVisible(Document document) {
        if (document == null || !policy.check(Action.VIEW_METADATA, resourceOf(document)).allowed()) {
            throw notFound();
        }
        return document;
    }

    public Document require(Action action, Document document) {
        requireVisible(document);
        if (action != Action.VIEW_METADATA && !policy.check(action, resourceOf(document)).allowed()) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "You are not allowed to perform this action.");
        }
        return document;
    }

    /**
     * {@code VIEW_AUDIT} on the document. Auditors need not see the document itself (security admins hold
     * {@code VIEW_AUDIT} without {@code VIEW_METADATA}); a caller with neither gets 404, so existence stays hidden.
     */
    public Document requireAuditable(Document document) {
        if (document == null) {
            throw notFound();
        }
        if (!policy.check(Action.VIEW_AUDIT, resourceOf(document)).allowed()) {
            if (can(Action.VIEW_METADATA, document)) {
                throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, "You are not allowed to perform this action.");
            }
            throw notFound();
        }
        return document;
    }

    /** Decision without audit, for UI hints and handler visibility. */
    public boolean can(Action action, Document document) {
        return policy.decide(Subject.current(), action, resourceOf(document)).allowed();
    }

    public void requireUpload(ResourceRef.NewDocument target) {
        if (!policy.check(Action.UPLOAD, target).allowed()) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED,
                    "You are not allowed to upload documents of this type to this branch.");
        }
    }

    public static ApiException notFound() {
        return ApiException.notFound(DOCUMENT_NOT_FOUND, "Document not found.");
    }
}
