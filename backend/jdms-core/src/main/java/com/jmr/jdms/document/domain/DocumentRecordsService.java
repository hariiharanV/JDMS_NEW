package com.jmr.jdms.document.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.contracts.DocumentChanged;
import com.jmr.jdms.document.api.DocumentRecords;
import com.jmr.jdms.document.api.RecordDocument;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link DocumentRecords} over the document tables (current tenant). */
@Service
class DocumentRecordsService implements DocumentRecords {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final RenditionRepository renditions;
    private final ContentStore contentStore;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    DocumentRecordsService(DocumentRepository documents, DocumentVersionRepository versions,
            RenditionRepository renditions, ContentStore contentStore, AuditTrail auditTrail,
            ApplicationEventPublisher events, TransactionTemplate transactions) {
        this.documents = documents;
        this.versions = versions;
        this.renditions = renditions;
        this.contentStore = contentStore;
        this.auditTrail = auditTrail;
        this.events = events;
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordDocument> find(UUID documentId) {
        return documents.findById(documentId).map(DocumentRecordsService::record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordDocument> ofTypes(Set<UUID> documentTypeIds, UUID afterId, int limit) {
        if (documentTypeIds.isEmpty()) {
            return List.of();
        }
        return documents.findForRetention(documentTypeIds, afterId == null ? new UUID(0, 0) : afterId,
                Limit.of(limit)).stream().map(DocumentRecordsService::record).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> idsByKeys(String cif, String accountNo, String contractRef) {
        if (cif == null && accountNo == null && contractRef == null) {
            return List.of();
        }
        Specification<Document> byKeys = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (cif != null) {
                predicates.add(builder.equal(root.get("cif"), cif));
            }
            if (accountNo != null) {
                predicates.add(builder.equal(root.get("accountNo"), accountNo));
            }
            if (contractRef != null) {
                predicates.add(builder.equal(root.get("contractRef"), contractRef));
            }
            predicates.add(builder.notEqual(root.get("status"), DocumentStatus.DISPOSED));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return documents.findAll(byKeys).stream().map(Document::getId).toList();
    }

    @Override
    @Transactional
    public void setLegalHold(Collection<UUID> documentIds, boolean held) {
        for (Document document : documents.findAllById(documentIds)) {
            if (document.isOnLegalHold() != held) {
                document.placeOnHold(held);
                events.publishEvent(new DocumentChanged(document.getTenantId(), document.getId()));
            }
        }
        documents.flush();
    }

    /** Blobs first: if the metadata update then fails, a retry finds the blobs gone and completes it. */
    @Override
    public List<String> dispose(UUID documentId) {
        List<StoredContent> blobs = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        transactions.executeWithoutResult(status -> {
            for (DocumentVersion version : versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId)) {
                blobs.add(version.content());
                hashes.add(version.getSha256());
                renditions.findByVersionId(version.getId()).forEach(rendition -> blobs.add(rendition.content()));
            }
        });
        blobs.forEach(blob -> contentStore.delete(blob.blobKey()));
        transactions.executeWithoutResult(status -> {
            Document document = documents.findById(documentId).orElseThrow();
            for (DocumentVersion version : versions.findByDocumentIdOrderByMajorDescMinorDesc(documentId)) {
                version.markDestroyed();
                renditions.deleteAll(renditions.findByVersionId(version.getId()));
            }
            document.dispose();
            documents.flush();
            auditTrail.record(AuditEntry.success("DOCUMENT_DISPOSED", DocumentAuditActions.RESOURCE_DOCUMENT,
                    documentId, Map.of("versions", hashes.size(), "blobsDestroyed", blobs.size())));
            events.publishEvent(new DocumentChanged(document.getTenantId(), documentId));
        });
        return hashes;
    }

    private static RecordDocument record(Document document) {
        return new RecordDocument(document.getId(), document.getDocumentTypeId(), document.getStatus().name(),
                document.getCreatedAt(), document.getExpiryDate(), document.getAccountNo(), document.isOnLegalHold(),
                document.getRecordDeclaredAt() != null);
    }
}
