package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentChanged;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.workflow.api.ApprovalOutcome;
import com.jmr.jdms.workflow.api.ApprovalSubject;
import com.jmr.jdms.workflow.api.ApprovalSubjectHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Uploads ({@code DOCUMENT}) and new major versions ({@code VERSION}) as approval subjects (spec §4.4, §5). Approval
 * makes the document active or the version current; rejection rejects the document (first version) or only the
 * version; a return makes an uploaded document a draft until the maker resubmits it.
 */
@Component
class DocumentApprovals implements ApprovalSubjectHandler {

    static final String DOCUMENT = "DOCUMENT";
    static final String VERSION = "VERSION";

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentAccess access;
    private final DocumentViews views;
    private final MetadataCatalog catalog;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;

    DocumentApprovals(DocumentRepository documents, DocumentVersionRepository versions, DocumentAccess access,
            DocumentViews views, MetadataCatalog catalog, AuditTrail auditTrail, ApplicationEventPublisher events) {
        this.documents = documents;
        this.versions = versions;
        this.access = access;
        this.views = views;
        this.catalog = catalog;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    @Override
    public Set<String> subjectTypes() {
        return Set.of(DOCUMENT, VERSION);
    }

    @Override
    public boolean canView(ApprovalSubject subject) {
        return documents.findById(subject.documentId()).map(document -> access.can(Action.VIEW_METADATA, document))
                .orElse(false);
    }

    @Override
    public boolean canDecide(ApprovalSubject subject) {
        return documents.findById(subject.documentId()).map(document -> access.can(Action.APPROVE, document))
                .orElse(false);
    }

    /** Document type and category, branch, classification, and the custom fields (e.g. {@code amount}). */
    @Override
    public Map<String, Object> routingFacts(ApprovalSubject subject) {
        Document document = documents.findById(subject.documentId()).orElseThrow();
        DocumentTypeInfo type = catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
        DocumentFieldsInput fields = views.fieldsOf(document);
        Map<String, Object> facts = new HashMap<>();
        if (fields.customFields() != null) {
            fields.customFields().forEach((name, value) -> {
                if (value instanceof Number number) {
                    facts.put(name, new BigDecimal(number.toString()));
                } else if (value != null) {
                    facts.put(name, value.toString());
                }
            });
        }
        facts.put("documentTypeCode", type.code());
        facts.put("documentCategory", type.category());
        facts.put("branchCode", fields.branchCode());
        facts.put("classification", document.getClassification().name());
        return facts;
    }

    @Override
    public void onOutcome(ApprovalSubject subject, ApprovalOutcome outcome) {
        Document document = documents.findById(subject.documentId()).orElseThrow();
        DocumentVersion version = versions.lockForUpdate(subject.versionId(), document.getId()).orElseThrow();
        boolean upload = DOCUMENT.equals(subject.subjectType());
        switch (outcome) {
            case APPROVED -> {
                version.approve();
                if (upload) {
                    document.approvalOutcome(DocumentStatus.ACTIVE);
                }
                document.makeCurrent(version.getId());
                if (catalog.findDocumentType(document.getDocumentTypeId()).map(DocumentTypeInfo::declareRecordOnApproval)
                        .orElse(false) && document.getRecordDeclaredAt() == null) {
                    document.declareRecord(Instant.now().truncatedTo(ChronoUnit.MICROS));
                    auditTrail.record(AuditEntry.success("RECORD_DECLARED", DocumentAuditActions.RESOURCE_DOCUMENT,
                            document.getId(), Map.of("versionId", version.getId().toString())));
                }
            }
            case REJECTED -> {
                version.reject();
                if (upload) {
                    document.approvalOutcome(DocumentStatus.REJECTED);
                }
            }
            case RETURNED -> {
                if (upload) {
                    document.approvalOutcome(DocumentStatus.DRAFT);
                }
            }
            case RESUBMITTED -> {
                if (upload) {
                    document.approvalOutcome(DocumentStatus.PENDING_APPROVAL);
                }
            }
        }
        documents.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionId", version.getId().toString());
        details.put("version", version.number());
        details.put("processInstanceId", subject.processInstanceId());
        auditTrail.record(AuditEntry.success((upload ? "DOCUMENT_" : "VERSION_") + outcome.name(),
                DocumentAuditActions.RESOURCE_DOCUMENT, document.getId(), details));
        events.publishEvent(new DocumentChanged(document.getTenantId(), document.getId()));
    }
}
