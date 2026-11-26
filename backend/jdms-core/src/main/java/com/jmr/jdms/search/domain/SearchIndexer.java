package com.jmr.jdms.search.domain;

import com.jmr.jdms.contracts.DocumentChanged;
import com.jmr.jdms.contracts.DocumentCreated;
import com.jmr.jdms.contracts.DocumentJobPayload;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.MetadataChanged;
import com.jmr.jdms.contracts.VersionCreated;
import com.jmr.jdms.document.api.IndexableDocument;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.metadata.api.FieldInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.job.JobQueue;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps each tenant's search index in step with its documents (spec §4.6, ADR-0015). A document is indexed right
 * after every committed change (metadata immediately, content once processing has produced text); a failed attempt
 * becomes an {@code INDEX} job with retries. Writes are whole-document upserts with a growing version, so repeated or
 * late deliveries never duplicate or roll back an entry. Access is decided at query time from the indexed attributes
 * (cabinet, type, folder, branch, classification), so changing rules or grants needs no re-index.
 */
@Service
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    /** Control characters (the highlighter marks matches with two of them, see SearchService). */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    /** Bidi marks and embeddings (OCR wraps Arabic in them); they would stick to the words they surround. */
    private static final Pattern BIDI = Pattern.compile("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");

    private final SearchIndex index;
    private final IndexableDocuments documents;
    private final MetadataCatalog catalog;
    private final TenantDirectory tenants;
    private final JobQueue jobs;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final int maxTextChars;
    private final int rebuildPageSize;
    private final int rebuildPages;

    SearchIndexer(SearchIndex index, IndexableDocuments documents, MetadataCatalog catalog, TenantDirectory tenants,
            JobQueue jobs, TransactionTemplate transactions, JsonMapper jsonMapper,
            @Value("${jdms.search.max-text-chars:1000000}") int maxTextChars,
            @Value("${jdms.search.rebuild.page-size:500}") int rebuildPageSize,
            @Value("${jdms.search.rebuild.pages-in-flight:3}") int rebuildPages) {
        this.index = index;
        this.documents = documents;
        this.catalog = catalog;
        this.tenants = tenants;
        this.jobs = jobs;
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.maxTextChars = maxTextChars;
        this.rebuildPageSize = rebuildPageSize;
        this.rebuildPages = rebuildPages;
    }

    @Async
    @TransactionalEventListener
    void on(DocumentCreated event) {
        indexAfterCommit(event.tenantId(), event.documentId());
    }

    @Async
    @TransactionalEventListener
    void on(VersionCreated event) {
        indexAfterCommit(event.tenantId(), event.documentId());
    }

    @Async
    @TransactionalEventListener
    void on(MetadataChanged event) {
        indexAfterCommit(event.tenantId(), event.documentId());
    }

    @Async
    @TransactionalEventListener
    void on(DocumentChanged event) {
        indexAfterCommit(event.tenantId(), event.documentId());
    }

    private void indexAfterCommit(UUID tenantId, UUID documentId) {
        TenantScope.run(tenantId, () -> {
            try {
                index(documentId);
            } catch (RuntimeException e) {
                log.warn("Indexing document {} failed, queued for retry: {}", documentId, e.toString());
                transactions.executeWithoutResult(status -> jobs.enqueue(JobType.INDEX,
                        new DocumentJobPayload(tenantId, documentId)));
            }
        });
    }

    /** Indexes one document of the current tenant (removes it from the index if it no longer exists). */
    public void index(UUID documentId) {
        String tenantCode = tenantCode();
        index.ensureIndex(tenantCode);
        Optional<IndexableDocument> found = documents.find(documentId);
        if (found.isEmpty()) {
            index.delete(tenantCode, documentId.toString());
            return;
        }
        IndexableDocument document = found.get();
        index.put(tenantCode, entry(document));
        if (document.currentVersionId() != null && isFinal(document)) {
            documents.markIndexed(document.id(), document.currentVersionId());
        }
    }

    /**
     * Rebuilds the current tenant's index from the database: all documents into a new index (bulk writes, refresh
     * off), the alias switched over, then a second pass through the alias so that changes made during the first pass
     * are not lost. The second pass compares versions and rewrites only documents whose indexed version is behind.
     * Pages of documents are loaded with a few queries each, texts are read concurrently, and several pages are in
     * flight at once.
     */
    public int rebuild() {
        String tenantCode = tenantCode();
        String fresh = index.createIndex(tenantCode);
        int count;
        try {
            count = pass(page -> write(fresh, page, false));
            index.completeBulkLoad(fresh);
        } catch (RuntimeException e) {
            index.dropIndex(fresh); // a retry starts over; no half-built index is left behind
            throw e;
        }
        index.switchAlias(tenantCode, fresh);
        String alias = index.aliasOf(tenantCode);
        int caughtUp = pass(page -> write(alias, page, true));
        index.refresh(alias);
        log.info("Rebuilt the search index of tenant {}: {} documents, {} caught up after the switch", tenantCode,
                count, caughtUp);
        return count;
    }

    /** Runs {@code work} over all document IDs, page by page, with up to {@code rebuildPages} pages in flight. */
    private int pass(ToIntFunction<List<UUID>> work) {
        UUID tenantId = TenantScope.currentTenantId().orElseThrow();
        Semaphore inFlight = new Semaphore(rebuildPages);
        AtomicBoolean failed = new AtomicBoolean();
        List<Future<Integer>> pages = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            UUID after = null;
            List<UUID> page;
            do {
                page = documents.idsAfter(after, rebuildPageSize);
                if (!page.isEmpty()) {
                    inFlight.acquire();
                    List<UUID> ids = page;
                    pages.add(executor.submit(() -> {
                        try {
                            return TenantScope.call(tenantId, () -> work.applyAsInt(ids));
                        } catch (RuntimeException e) {
                            failed.set(true);
                            throw e;
                        } finally {
                            inFlight.release();
                        }
                    }));
                    after = page.getLast();
                }
            } while (page.size() == rebuildPageSize && !failed.get()); // a failed page ends the pass
            int count = 0;
            for (Future<Integer> written : pages) {
                count += written.get();
            }
            return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailableException("Rebuild interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Rebuild failed", e.getCause());
        }
    }

    /** Indexes one page; with {@code onlyBehind}, only documents whose indexed version is older or missing. */
    private int write(String target, List<UUID> ids, boolean onlyBehind) {
        List<IndexableDocument> page = documents.findAll(ids);
        if (onlyBehind) {
            Map<String, Long> indexed = index.versions(target, ids.stream().map(UUID::toString).toList());
            page = page.stream().filter(document -> indexed.getOrDefault(document.id().toString(), -1L)
                    < document.indexVersion()).toList();
        }
        if (page.isEmpty()) {
            return 0;
        }
        Map<UUID, String> texts = documents.currentTexts(page, maxTextChars);
        Map<UUID, List<FieldInfo>> fields = new HashMap<>();
        List<SearchIndex.Entry> entries = page.stream().map(document -> new SearchIndex.Entry(document.id().toString(),
                document.indexVersion(), source(document, Optional.ofNullable(texts.get(document.id())),
                        fields.computeIfAbsent(document.documentTypeId(), catalog::fields)))).toList();
        index.bulkPut(target, entries);
        return entries.size();
    }

    private SearchIndex.Entry entry(IndexableDocument document) {
        Optional<String> text = document.hasText() ? documents.currentText(document.id(), maxTextChars)
                : Optional.empty();
        return new SearchIndex.Entry(document.id().toString(), document.indexVersion(),
                source(document, text, catalog.fields(document.documentTypeId())));
    }

    ObjectNode source(IndexableDocument document, Optional<String> text, List<FieldInfo> fields) {
        ObjectNode source = jsonMapper.createObjectNode();
        source.put("document_id", document.id().toString());
        source.put("tenant_id", TenantScope.currentTenantId().orElseThrow().toString());
        source.put("cabinet_id", document.cabinetId().toString());
        source.put("cabinet_code", document.cabinetCode());
        source.put("document_type_id", document.documentTypeId().toString());
        source.put("document_type_code", document.documentTypeCode());
        putIfPresent(source, "folder_id", document.folderId());
        source.put("status", document.status());
        source.put("classification", document.classification());
        source.put("branch_id", document.branchId().toString());
        source.put("branch_code", document.branchCode());
        source.put("title", document.title());
        putIfPresent(source, "cif", document.cif());
        putIfPresent(source, "customer_name", document.customerName());
        putIfPresent(source, "account_no", document.accountNo());
        putIfPresent(source, "collateral_code", document.collateralCode());
        putIfPresent(source, "contract_ref", document.contractRef());
        putIfPresent(source, "transaction_ref", document.transactionRef());
        putIfPresent(source, "application_ref", document.applicationRef());
        putIfPresent(source, "external_ref", document.externalRef());
        putIfPresent(source, "expiry_date", document.expiryDate());
        putIfPresent(source, "created_at", document.createdAt());
        putIfPresent(source, "updated_at", document.updatedAt());
        putIfPresent(source, "current_version_id", document.currentVersionId());
        putIfPresent(source, "current_version", document.currentVersion());
        putIfPresent(source, "processing_status", document.processingStatus());
        source.put("has_text", text.isPresent());
        source.put("on_legal_hold", document.onLegalHold());
        text.ifPresent(content -> source.put("content", clean(content)));
        customFields(source, document, fields);
        return source;
    }

    /** Searchable or facet fields only; sensitive fields are never indexed. */
    private void customFields(ObjectNode source, IndexableDocument document, List<FieldInfo> fields) {
        for (FieldInfo field : fields) {
            Object value = document.customFields().get(field.key());
            if (value == null || field.sensitive() || !(field.searchable() || field.facet())) {
                continue;
            }
            switch (field.dataType()) {
                case "TEXT" -> source.withObject("custom_text").put(field.key(), value.toString());
                case "LOV" -> source.withObject("custom_keyword").put(field.key(), value.toString());
                case "NUMBER" -> source.withObject("custom_number").put(field.key(),
                        new BigDecimal(value.toString()));
                case "DATE" -> source.withObject("custom_date").put(field.key(), value.toString());
                case "BOOLEAN" -> source.withObject("custom_bool").put(field.key(),
                        Boolean.parseBoolean(value.toString()));
                default -> log.debug("Field type {} is not indexed", field.dataType());
            }
        }
    }

    static String clean(String text) {
        return CONTROL.matcher(BIDI.matcher(text).replaceAll("")).replaceAll(" ");
    }

    private static boolean isFinal(IndexableDocument document) {
        return document.processingStatus() != null && !document.processingStatus().equals("PENDING")
                && !document.processingStatus().equals("PROCESSING");
    }

    private String tenantCode() {
        UUID tenantId = TenantScope.currentTenantId().orElseThrow();
        return tenants.findById(tenantId).orElseThrow().code();
    }

    private static void putIfPresent(ObjectNode node, String name, Object value) {
        if (value != null) {
            node.put(name, value.toString());
        }
    }
}
