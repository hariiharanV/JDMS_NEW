package com.jmr.jdms.document.domain;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.document.api.IndexableDocument;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link IndexableDocuments} over the document tables (current tenant only). */
@Service
@Transactional(readOnly = true)
class DocumentIndexSource implements IndexableDocuments {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final RenditionRepository renditions;
    private final MetadataCatalog catalog;
    private final ContentStore contentStore;
    private final DocumentAccess access;
    private final BranchDirectory branches;
    private final int readConcurrency;

    DocumentIndexSource(DocumentRepository documents, DocumentVersionRepository versions,
            RenditionRepository renditions, MetadataCatalog catalog, ContentStore contentStore,
            DocumentAccess access, BranchDirectory branches,
            @Value("${jdms.search.rebuild.read-concurrency:16}") int readConcurrency) {
        this.documents = documents;
        this.versions = versions;
        this.renditions = renditions;
        this.catalog = catalog;
        this.contentStore = contentStore;
        this.access = access;
        this.branches = branches;
        this.readConcurrency = readConcurrency;
    }

    @Override
    public Set<UUID> permitted(Collection<UUID> documentIds, Action action) {
        return documents.findAllById(documentIds).stream().filter(document -> access.can(action, document))
                .map(Document::getId).collect(Collectors.toSet());
    }

    @Override
    public Optional<IndexableDocument> find(UUID documentId) {
        return documents.findById(documentId).map(this::indexable);
    }

    @Override
    public List<IndexableDocument> findAll(List<UUID> documentIds) {
        Map<UUID, Document> found = documents.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Set<UUID> versionIds = found.values().stream().map(Document::getCurrentVersionId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, DocumentVersion> current = versions.findAllById(versionIds).stream()
                .collect(Collectors.toMap(DocumentVersion::getId, Function.identity()));
        Set<UUID> withText = versionIds.isEmpty() ? Set.of()
                : renditions.findByVersionIdInAndKind(versionIds, Rendition.TEXT).stream()
                        .map(Rendition::getVersionId).collect(Collectors.toSet());
        Map<UUID, String> branchCodes = branches.findByIds(found.values().stream().map(Document::getBranchId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(BranchInfo::id, BranchInfo::code));
        Map<UUID, DocumentTypeInfo> types = new HashMap<>();
        return documentIds.stream().map(found::get).filter(Objects::nonNull).map(document -> {
            DocumentVersion version = document.getCurrentVersionId() == null ? null
                    : current.get(document.getCurrentVersionId());
            if (version != null && !version.getDocumentId().equals(document.getId())) {
                version = null; // never another document's version
            }
            DocumentTypeInfo type = types.computeIfAbsent(document.getDocumentTypeId(),
                    id -> catalog.findDocumentType(id).orElseThrow());
            return indexable(document, version, version != null && withText.contains(version.getId()), type,
                    branchCodes.get(document.getBranchId()));
        }).toList();
    }

    @Override
    public List<UUID> idsAfter(UUID afterId, int limit) {
        return documents.findIdsAfter(afterId == null ? new UUID(0, 0) : afterId, Limit.of(limit));
    }

    @Override
    public Optional<String> currentText(UUID documentId, int maxChars) {
        return documents.findById(documentId).map(Document::getCurrentVersionId)
                .flatMap(versionId -> renditions.findByVersionIdAndKind(versionId, Rendition.TEXT))
                .map(text -> read(TenantScope.currentTenantId().orElseThrow(), text, maxChars));
    }

    private String read(UUID tenantId, Rendition text, int maxChars) {
        try (InputStream in = contentStore.open(tenantId, text.content())) {
            String value = new String(in.readNBytes(4 * maxChars), StandardCharsets.UTF_8);
            return value.length() > maxChars ? value.substring(0, maxChars) : value;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the text rendition", e);
        }
    }

    @Override
    public Map<UUID, String> currentTexts(Collection<IndexableDocument> indexable, int maxChars) {
        Map<UUID, UUID> documentOfVersion = indexable.stream().filter(IndexableDocument::hasText)
                .collect(Collectors.toMap(IndexableDocument::currentVersionId, IndexableDocument::id));
        if (documentOfVersion.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = TenantScope.currentTenantId().orElseThrow();
        List<Rendition> texts = renditions.findByVersionIdInAndKind(documentOfVersion.keySet(), Rendition.TEXT);
        // blob reads and decryption only (no database work) run concurrently
        Semaphore permits = new Semaphore(readConcurrency);
        Map<UUID, Future<String>> reads = new HashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Rendition text : texts) {
                reads.put(documentOfVersion.get(text.getVersionId()), executor.submit(() -> {
                    permits.acquire();
                    try {
                        return read(tenantId, text, maxChars);
                    } finally {
                        permits.release();
                    }
                }));
            }
            Map<UUID, String> result = new HashMap<>();
            for (Map.Entry<UUID, Future<String>> read : reads.entrySet()) {
                result.put(read.getKey(), read.getValue().get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading text renditions", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Could not read a text rendition", e.getCause());
        }
    }

    @Override
    @Transactional
    public void markIndexed(UUID documentId, UUID versionId) {
        versions.lockForUpdate(versionId, documentId).ifPresent(DocumentVersion::markIndexed);
    }

    private IndexableDocument indexable(Document document) {
        DocumentTypeInfo type = catalog.findDocumentType(document.getDocumentTypeId()).orElseThrow();
        DocumentVersion current = document.getCurrentVersionId() == null ? null
                : versions.findByIdAndDocumentId(document.getCurrentVersionId(), document.getId()).orElse(null);
        boolean hasText = current != null
                && renditions.findByVersionIdAndKind(current.getId(), Rendition.TEXT).isPresent();
        String branchCode = branches.findByIds(List.of(document.getBranchId())).stream().map(BranchInfo::code)
                .findFirst().orElse(null);
        return indexable(document, current, hasText, type, branchCode);
    }

    private static IndexableDocument indexable(Document document, DocumentVersion current, boolean hasText,
            DocumentTypeInfo type, String branchCode) {
        DocumentMetadata metadata = document.metadata();
        StandardMetadata fields = metadata.standard();
        Map<String, Object> customFields = CustomFieldsCodec.read(metadata.customFieldsJson());
        boolean finished = current != null && current.getProcessingStatus() != ProcessingStatus.PENDING
                && current.getProcessingStatus() != ProcessingStatus.PROCESSING;
        long indexVersion = document.getVersionLock() * 16 + (current == null ? 0 : current.getVersionLock() * 4)
                + (hasText ? 2 : 0) + (finished ? 1 : 0);
        return new IndexableDocument(document.getId(), document.getCabinetId(), type.cabinetCode(),
                document.getDocumentTypeId(), type.code(), metadata.folderId(), metadata.title(),
                document.getStatus().name(), metadata.classification().name(), document.getBranchId(), branchCode,
                fields.cif(), fields.customerName(), fields.accountNo(), fields.collateralCode(), fields.contractRef(),
                fields.transactionRef(), fields.applicationRef(), metadata.externalRef(), fields.expiryDate(),
                customFields == null ? Map.of() : customFields, document.getCreatedAt(), document.getUpdatedAt(),
                document.getCurrentVersionId(), current == null ? null : current.number(),
                current == null ? null : current.getProcessingStatus().name(), hasText, document.isOnLegalHold(),
                indexVersion);
    }
}
