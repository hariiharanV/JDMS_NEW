package com.jmr.jdms.document.domain;

/** Audit actions and resource types of the document module. */
final class DocumentAuditActions {

    static final String RESOURCE_DOCUMENT = "DOCUMENT";
    static final String UPLOADED = "DOCUMENT_UPLOADED";
    static final String DOWNLOADED = "DOCUMENT_DOWNLOADED";
    static final String VIEWED = "DOCUMENT_VIEWED";
    /** A write repeated with the same Idempotency-Key and request: the original response is returned, nothing changes. */
    static final String REQUEST_REPLAYED = "DOCUMENT_REQUEST_REPLAYED";
    static final String CHECKED_OUT = "DOCUMENT_CHECKED_OUT";
    static final String CHECKOUT_CANCELLED = "DOCUMENT_CHECKOUT_CANCELLED";
    static final String DRAFT_UPLOADED = "DOCUMENT_DRAFT_UPLOADED";
    static final String CHECKED_IN = "DOCUMENT_CHECKED_IN";
    static final String VERSION_RESTORED = "DOCUMENT_VERSION_RESTORED";
    static final String METADATA_CHANGED = "DOCUMENT_METADATA_CHANGED";
    static final String STORAGE_INTEGRITY_FAILED = "STORAGE_INTEGRITY_FAILED";

    private DocumentAuditActions() {
    }
}
