package com.jmr.jdms.document.domain;

import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds {@link DocumentView}s, resolving codes for cabinet, type and branch. */
@Component
public class DocumentViews {

    private final MetadataCatalog catalog;
    private final BranchDirectory branches;
    private final DocumentVersionRepository versions;

    DocumentViews(MetadataCatalog catalog, BranchDirectory branches, DocumentVersionRepository versions) {
        this.catalog = catalog;
        this.branches = branches;
        this.versions = versions;
    }

    public DocumentView view(Document document) {
        DocumentVersion current = document.getCurrentVersionId() == null ? null
                : versions.findById(document.getCurrentVersionId()).orElse(null);
        return view(document, current);
    }

    public DocumentView view(Document document, DocumentVersion current) {
        DocumentMetadata metadata = document.metadata();
        String branchCode = branches.findByIds(List.of(document.getBranchId())).stream().map(BranchInfo::code)
                .findFirst().orElse(null);
        return new DocumentView(document.getId(), catalog.cabinetCode(document.getCabinetId()).orElse(null),
                catalog.findDocumentType(document.getDocumentTypeId()).map(DocumentTypeInfo::code).orElse(null),
                metadata.folderId(), metadata.title(), document.getStatus(), metadata.classification(), branchCode,
                metadata.standard(), CustomFieldsCodec.read(metadata.customFieldsJson()),
                current == null ? null : VersionView.of(current), document.getCheckedOutBy(),
                document.getCheckedOutAt(), document.getSourceSystem(), metadata.externalRef(),
                document.isOnLegalHold(), document.getCreatedAt(), document.getCreatedBy(), document.getUpdatedAt(),
                document.getVersionLock());
    }

    /** The client-side form of a document's metadata (what a PATCH merge-patch applies to). */
    public DocumentFieldsInput fieldsOf(Document document) {
        DocumentView view = view(document, null);
        StandardMetadata s = view.standardFields();
        return new DocumentFieldsInput(view.cabinetCode(), view.documentTypeCode(), view.title(),
                view.classification(), view.branchCode(), view.folderId(), s.cif(), s.customerName(), s.accountNo(),
                s.collateralCode(), s.contractRef(), s.transactionRef(), s.applicationRef(), s.expiryDate(),
                s.physicalLocation(), s.physicalRef(), view.externalRef(), view.customFields());
    }
}
