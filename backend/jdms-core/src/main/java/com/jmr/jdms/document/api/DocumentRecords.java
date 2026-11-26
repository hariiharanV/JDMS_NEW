package com.jmr.jdms.document.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Records management on the current tenant's documents (spec §4.8): holds, retention candidates, disposition. */
public interface DocumentRecords {

    Optional<RecordDocument> find(UUID documentId);

    /** Documents of these types that are not disposed, in ID order after {@code afterId} (null: from the start). */
    List<RecordDocument> ofTypes(Set<UUID> documentTypeIds, UUID afterId, int limit);

    /** IDs of documents with any of these business keys (null keys are ignored). */
    List<UUID> idsByKeys(String cif, String accountNo, String contractRef);

    /** Sets the legal-hold flag (the records module decides it from all active holds). */
    void setLegalHold(Collection<UUID> documentIds, boolean held);

    /**
     * Destroys every blob of the document (versions and renditions), marks its versions {@code DESTROYED} and reduces
     * the document to a disposed shell. Fails if a blob cannot be deleted (e.g. still under object lock).
     *
     * @return the SHA-256 of each destroyed version, for the tombstone
     */
    List<String> dispose(UUID documentId);
}
