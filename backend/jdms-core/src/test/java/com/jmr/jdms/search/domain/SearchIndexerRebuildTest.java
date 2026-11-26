package com.jmr.jdms.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.document.api.IndexableDocument;
import com.jmr.jdms.document.api.IndexableDocuments;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import com.jmr.jdms.tenant.api.TenantInfo;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rebuild's paging and passes (B-13): every document lands in the new index in bulk, across pages, with refreshes
 * off until the load completes; the second pass rewrites only documents that changed during the first one.
 */
class SearchIndexerRebuildTest {

    static final UUID TENANT = UUID.randomUUID();
    static final String ALIAS = "jdms-perf-documents";

    final FakeIndex index = new FakeIndex();
    final FakeDocuments documents = new FakeDocuments();

    @Test
    void everyDocumentIsWrittenInBulkAcrossPagesAndRefreshedOnceLoaded() {
        documents.add(7);
        int count = TenantScope.call(TENANT, () -> indexer(2).rebuild());

        assertThat(count).isEqualTo(7);
        assertThat(index.documents("new")).containsOnlyKeys(documents.ids());
        assertThat(index.documents("new").get(documents.ids().getFirst()).path("content").asString())
                .isEqualTo("text of 0");
        assertThat(index.calls).containsSubsequence("create", "bulk new", "complete new", "switch new", "refresh "
                + ALIAS);
        assertThat(index.calls.stream().filter(call -> call.equals("bulk new")).count()).isEqualTo(4); // pages of 2
        assertThat(index.calls).doesNotContain("put");
        assertThat(index.calls).doesNotContain("bulk " + ALIAS); // nothing changed during the first pass
    }

    @Test
    void documentsChangedDuringTheFirstPassAreCaughtUpAfterTheSwitch() {
        documents.add(5);
        String changed = documents.ids().get(3);
        index.afterSwitch = () -> documents.bumpVersion(changed); // a write lands on the old index meanwhile

        TenantScope.call(TENANT, () -> indexer(2).rebuild());

        assertThat(index.bulkIds(ALIAS)).containsExactly(changed);
        assertThat(index.versions.get(changed)).isEqualTo(documents.version(changed));
    }

    @Test
    void aFailedRebuildLeavesNoHalfBuiltIndexAndKeepsTheOldOne() {
        documents.add(5);
        documents.failOn = documents.ids.get(3); // e.g. a text rendition that cannot be read

        assertThatThrownBy(() -> TenantScope.call(TENANT, () -> indexer(2).rebuild()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(index.calls).contains("create", "drop new").doesNotContain("switch new");
        assertThat(index.indices).doesNotContainKey("new");
    }

    SearchIndexer indexer(int pageSize) {
        MetadataCatalog catalog = proxy(MetadataCatalog.class, (method, args) -> method.equals("fields") ? List.of()
                : null);
        TenantDirectory tenants = proxy(TenantDirectory.class, (method, args) -> Optional.of(new TenantInfo(TENANT,
                "PERF", "Perf", "en", List.of("en"))));
        return new SearchIndexer(index, documents, catalog, tenants, null, null, JsonMapper.builder().build(), 1000,
                pageSize, 2);
    }

    interface Answer {
        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> answer.answer(method.getName(), args));
    }

    /** Documents 0..n-1 in ID order; each has text and a version that changes when bumped. */
    static class FakeDocuments implements IndexableDocuments {

        final Map<String, Long> versions = new ConcurrentHashMap<>();
        final List<UUID> ids = new ArrayList<>();

        void add(int count) {
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(0, i + 1);
                ids.add(id);
                versions.put(id.toString(), 17L);
            }
        }

        List<String> ids() {
            return ids.stream().map(UUID::toString).toList();
        }

        void bumpVersion(String id) {
            versions.merge(id, 16L, Long::sum);
        }

        long version(String id) {
            return versions.get(id);
        }

        @Override
        public List<UUID> idsAfter(UUID afterId, int limit) {
            return ids.stream().filter(id -> afterId == null || id.compareTo(afterId) > 0).limit(limit).toList();
        }

        UUID failOn;

        @Override
        public List<IndexableDocument> findAll(List<UUID> documentIds) {
            assertThat(TenantScope.currentTenantId()).contains(TENANT); // pages run on other threads
            if (documentIds.contains(failOn)) {
                throw new IllegalStateException("Could not read the text rendition");
            }
            return documentIds.stream().map(this::document).toList();
        }

        @Override
        public Optional<IndexableDocument> find(UUID documentId) {
            return Optional.of(document(documentId));
        }

        @Override
        public Map<UUID, String> currentTexts(Collection<IndexableDocument> documents, int maxChars) {
            return documents.stream().collect(Collectors.toMap(IndexableDocument::id,
                    document -> "text of " + ids.indexOf(document.id())));
        }

        @Override
        public Optional<String> currentText(UUID documentId, int maxChars) {
            return Optional.of("text of " + ids.indexOf(documentId));
        }

        @Override
        public void markIndexed(UUID documentId, UUID versionId) {
        }

        @Override
        public Set<UUID> permitted(Collection<UUID> documentIds, Action action) {
            return Set.copyOf(documentIds);
        }

        IndexableDocument document(UUID id) {
            UUID any = new UUID(1, 1);
            return new IndexableDocument(id, any, "CUSTOMERS", any, "PASSPORT", null, "Title", "ACTIVE",
                    "INTERNAL", any, "001", null, null, null, null, null, null, null, null, null, Map.of(),
                    Instant.EPOCH, Instant.EPOCH, any, "1.0", "DONE", true, false, versions.get(id.toString()));
        }
    }

    /** An in-memory index: one rebuild index, then the alias pointing at it. */
    static class FakeIndex implements SearchIndex {

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Map<String, JsonNode>> indices = new ConcurrentHashMap<>();
        final Map<String, Long> versions = new ConcurrentHashMap<>();
        final List<String> aliasBulkIds = Collections.synchronizedList(new ArrayList<>());
        Runnable afterSwitch = () -> { };
        boolean refreshing = true;

        Map<String, JsonNode> documents(String index) {
            return new TreeMap<>(indices.getOrDefault(index, Map.of()));
        }

        List<String> bulkIds(String target) {
            return ALIAS.equals(target) ? List.copyOf(aliasBulkIds) : List.of();
        }

        @Override
        public String aliasOf(String tenantCode) {
            return ALIAS;
        }

        @Override
        public void ensureIndex(String tenantCode) {
        }

        @Override
        public void put(String tenantCode, Entry entry) {
            calls.add("put");
        }

        @Override
        public void delete(String tenantCode, String documentId) {
        }

        @Override
        public JsonNode search(String tenantCode, ObjectNode body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createIndex(String tenantCode) {
            calls.add("create");
            refreshing = false;
            indices.put("new", new ConcurrentHashMap<>());
            return "new";
        }

        @Override
        public void bulkPut(String target, List<Entry> entries) {
            calls.add("bulk " + target);
            if (ALIAS.equals(target)) {
                entries.forEach(entry -> aliasBulkIds.add(entry.documentId()));
            }
            entries.forEach(entry -> {
                if (versions.getOrDefault(entry.documentId(), -1L) <= entry.version()) {
                    indices.get("new").put(entry.documentId(), entry.source());
                    versions.put(entry.documentId(), entry.version());
                }
            });
        }

        @Override
        public void dropIndex(String index) {
            calls.add("drop " + index);
            indices.remove(index);
        }

        @Override
        public void completeBulkLoad(String index) {
            calls.add("complete " + index);
            refreshing = true;
        }

        @Override
        public void refresh(String target) {
            calls.add("refresh " + target);
        }

        @Override
        public Map<String, Long> versions(String target, Collection<String> documentIds) {
            assertThat(target).isEqualTo(ALIAS);
            return documentIds.stream().filter(versions::containsKey)
                    .collect(Collectors.toMap(id -> id, versions::get));
        }

        @Override
        public void switchAlias(String tenantCode, String index) {
            assertThat(refreshing).as("refreshes are back on before searches reach the new index").isTrue();
            calls.add("switch " + index);
            afterSwitch.run();
        }
    }
}
