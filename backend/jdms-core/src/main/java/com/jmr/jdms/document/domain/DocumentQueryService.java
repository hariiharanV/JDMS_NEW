package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.SearchFilter;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditEventView;
import com.jmr.jdms.audit.api.AuditQueries;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.workflow.api.ApprovalRecordView;
import com.jmr.jdms.workflow.api.Approvals;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Document reads, limited to documents the caller may see (access policy, spec §6). */
@Service
@Transactional(readOnly = true)
public class DocumentQueryService {

    /** Events returned by the document audit endpoint; the tenant-wide audit query pages through any number. */
    static final int AUDIT_LIMIT = 1000;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentAccess access;
    private final DocumentViews views;
    private final MetadataCatalog catalog;
    private final AccessPolicy policy;
    private final Approvals approvals;
    private final AuditQueries audit;
    private final AuditTrail auditTrail;

    DocumentQueryService(DocumentRepository documents, DocumentVersionRepository versions, DocumentAccess access,
            DocumentViews views, MetadataCatalog catalog, AccessPolicy policy, Approvals approvals,
            AuditQueries audit, AuditTrail auditTrail) {
        this.documents = documents;
        this.versions = versions;
        this.access = access;
        this.views = views;
        this.catalog = catalog;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.auditTrail = auditTrail;
    }

    /** Filters of {@code GET /documents}; all optional. */
    public record DocumentFilter(String documentTypeCode, UUID folderId, String cif, String accountNo,
            String contractRef, String applicationRef, DocumentStatus status, UUID cabinetId, String externalRef) {

        public DocumentFilter(String documentTypeCode, UUID folderId, String cif, String accountNo, String contractRef,
                String applicationRef, DocumentStatus status, UUID cabinetId) {
            this(documentTypeCode, folderId, cif, accountNo, contractRef, applicationRef, status, cabinetId, null);
        }
    }

    public DocumentView get(UUID documentId) {
        return views.view(access.requireVisible(documents.findById(documentId).orElse(null)));
    }

    /** A caller opening the document (spec §4.11 "view"): {@link #get} plus the caller's allowed actions and a
     * {@code DOCUMENT_VIEWED} event. */
    @Transactional
    public DocumentDetailView view(UUID documentId) {
        Document document = access.requireVisible(documents.findById(documentId).orElse(null));
        auditTrail.record(AuditEntry.success(DocumentAuditActions.VIEWED, DocumentAuditActions.RESOURCE_DOCUMENT,
                documentId, Map.of()));
        return new DocumentDetailView(views.view(document), DocumentDetailView.DOCUMENT_ACTIONS.stream()
                .filter(action -> access.can(action, document)).toList());
    }

    /** Approval history (spec §4.4): uploads, versions and metadata changes of the document, oldest first. */
    public List<ApprovalRecordView> history(UUID documentId) {
        access.requireVisible(documents.findById(documentId).orElse(null));
        return approvals.history(documentId);
    }

    /** Audit events of the document, oldest first ({@code VIEW_AUDIT}); the read is itself audited. */
    @Transactional
    public List<AuditEventView> audit(UUID documentId) {
        access.requireAuditable(documents.findById(documentId).orElse(null));
        return audit.forResource(DocumentAuditActions.RESOURCE_DOCUMENT, documentId.toString(), AUDIT_LIMIT);
    }

    public List<VersionView> versions(UUID documentId) {
        access.requireVisible(documents.findById(documentId).orElse(null));
        return versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId).stream().map(VersionView::of).toList();
    }

    /**
     * Filtered list limited to documents the caller may see: the access policy's search filter is part of the query,
     * so {@code total} counts only visible documents.
     */
    public Page<DocumentView> list(DocumentFilter filter, Pageable pageable) {
        SearchFilter access = policy.searchFilter(Subject.current(), Action.VIEW_METADATA);
        if (access.matchesNothing()) {
            return Page.empty(pageable);
        }
        UUID typeId = filter.documentTypeCode() == null ? null
                : catalog.findDocumentType(filter.documentTypeCode()).map(DocumentTypeInfo::id)
                        .orElse(new UUID(0, 0));
        Specification<Document> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("classification").in(access.classifications()));
            for (SearchFilter.Scope deny : access.denyScopes()) {
                predicates.add(builder.not(scope(deny, root, builder)));
            }
            List<Predicate> allowed = new ArrayList<>();
            for (SearchFilter.Scope allow : access.allowScopes()) {
                Predicate branch = allow.allBranches() ? builder.conjunction()
                        : allow.branchIds().isEmpty() ? builder.disjunction() : root.get("branchId").in(allow.branchIds());
                allowed.add(builder.and(scope(allow, root, builder), branch));
            }
            if (!access.grantedDocumentIds().isEmpty()) {
                allowed.add(root.get("id").in(access.grantedDocumentIds()));
            }
            predicates.add(builder.or(allowed.toArray(Predicate[]::new)));
            if (typeId != null) {
                predicates.add(builder.equal(root.get("documentTypeId"), typeId));
            }
            if (filter.cabinetId() != null) {
                predicates.add(builder.equal(root.get("cabinetId"), filter.cabinetId()));
            }
            if (filter.folderId() != null) {
                predicates.add(builder.equal(root.get("folderId"), filter.folderId()));
            }
            if (filter.cif() != null) {
                predicates.add(builder.equal(root.get("cif"), filter.cif()));
            }
            if (filter.accountNo() != null) {
                predicates.add(builder.equal(root.get("accountNo"), filter.accountNo()));
            }
            if (filter.contractRef() != null) {
                predicates.add(builder.equal(root.get("contractRef"), filter.contractRef()));
            }
            if (filter.applicationRef() != null) {
                predicates.add(builder.equal(root.get("applicationRef"), filter.applicationRef()));
            }
            if (filter.externalRef() != null) {
                predicates.add(builder.equal(root.get("externalRef"), filter.externalRef()));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return documents.findAll(specification, pageable).map(views::view);
    }

    private Predicate scope(SearchFilter.Scope scope, Root<Document> root, CriteriaBuilder builder) {
        return switch (scope.type()) {
            case TENANT -> builder.conjunction();
            case CABINET -> builder.equal(root.get("cabinetId"), scope.id());
            case DOCUMENT_TYPE -> builder.equal(root.get("documentTypeId"), scope.id());
            case DOCUMENT -> builder.equal(root.get("id"), scope.id());
            case FOLDER -> {
                Set<UUID> subtree = catalog.folderSubtree(scope.id());
                yield subtree.isEmpty() ? builder.disjunction() : root.get("folderId").in(subtree);
            }
        };
    }
}
