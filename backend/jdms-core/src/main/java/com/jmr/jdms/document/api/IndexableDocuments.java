package com.jmr.jdms.document.api;

import com.jmr.jdms.access.api.Action;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read access to the current tenant's documents for the search index and search results (B-07). */
public interface IndexableDocuments {

    Optional<IndexableDocument> find(UUID documentId);

    /** The documents among these IDs that exist, in the order given; a few queries for the whole list (rebuilds). */
    List<IndexableDocument> findAll(List<UUID> documentIds);

    /** Document IDs in ID order after {@code afterId} (null: from the start), for full rebuilds. */
    List<UUID> idsAfter(UUID afterId, int limit);

    /** The text of the document's current version (extracted or OCR), cut at {@code maxChars}. */
    Optional<String> currentText(UUID documentId, int maxChars);

    /** The current text of each document that has one, by document ID; blobs are read concurrently (rebuilds). */
    Map<UUID, String> currentTexts(Collection<IndexableDocument> documents, int maxChars);

    /** Records that the version's final state (text, processing outcome) is in the index. */
    void markIndexed(UUID documentId, UUID versionId);

    /** Of these documents, the ones the caller may perform {@code action} on now (current data and rules). */
    Set<UUID> permitted(Collection<UUID> documentIds, Action action);
}
