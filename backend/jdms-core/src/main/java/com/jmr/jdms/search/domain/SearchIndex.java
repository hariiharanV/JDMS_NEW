package com.jmr.jdms.search.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The search engine as the search module needs it: one index alias per tenant (implemented over OpenSearch). */
public interface SearchIndex {

    /** One document to write. {@code version} only ever grows: an older write never replaces a newer one. */
    record Entry(String documentId, long version, ObjectNode source) {
    }

    /** The name of the tenant's alias (the target of searches and writes). */
    String aliasOf(String tenantCode);

    /** Creates the tenant's index and alias if missing. */
    void ensureIndex(String tenantCode);

    /** Writes through the tenant's alias; searchable when it returns. */
    void put(String tenantCode, Entry entry);

    void delete(String tenantCode, String documentId);

    JsonNode search(String tenantCode, ObjectNode body);

    /** A new, empty index for a rebuild (not yet behind the alias), loading without refreshes until completed. */
    String createIndex(String tenantCode);

    /** Writes many entries to an index or alias (versioned like {@link #put}); searchable after a refresh. */
    void bulkPut(String target, List<Entry> entries);

    /** Deletes a rebuild index that never went behind the alias (a failed rebuild). */
    void dropIndex(String index);

    /** Ends the bulk load of a rebuild index: normal refreshes again, everything written so far searchable. */
    void completeBulkLoad(String index);

    /** Makes everything written to the index or alias searchable. */
    void refresh(String target);

    /** The versions held in the index or alias for these documents (absent: not indexed). */
    Map<String, Long> versions(String target, Collection<String> documentIds);

    /** Points the tenant's alias at {@code index} atomically and drops the previous indices. */
    void switchAlias(String tenantCode, String index);
}
