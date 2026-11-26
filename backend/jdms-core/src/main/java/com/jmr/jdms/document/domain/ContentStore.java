package com.jmr.jdms.document.domain;

import java.io.InputStream;
import java.util.UUID;

/**
 * Stores and reads document content, encrypted at rest with a per-object data key wrapped by the tenant key
 * (envelope encryption, CLAUDE.md rule 7). The core never parses content (rule 6): it only encrypts, hashes, counts
 * and streams bytes.
 */
public interface ContentStore {

    /**
     * Encrypts and stores the stream under a new internal key, hashing and counting the plaintext.
     *
     * @throws ContentTooLargeException when the content exceeds {@code maxBytes} (nothing is kept)
     * @throws EmptyContentException    when the content is empty (nothing is kept)
     */
    StoredContent store(UUID tenantId, String tenantCode, InputStream plaintext, long maxBytes);

    /**
     * Opens the decrypted content. Each encrypted segment is authenticated before its bytes are returned.
     *
     * @throws ContentUnavailableException when the blob is missing or cannot be decrypted
     */
    InputStream open(UUID tenantId, StoredContent content);

    /** Removes a blob (failed uploads, discarded drafts). Missing blobs are ignored. */
    void delete(String blobKey);
}
