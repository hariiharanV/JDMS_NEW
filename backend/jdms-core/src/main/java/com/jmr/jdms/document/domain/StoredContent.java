package com.jmr.jdms.document.domain;

/**
 * Encrypted content as stored: internal blob key, SHA-256 and size of the plaintext, and the wrapped data key.
 */
public record StoredContent(String blobKey, String sha256, long sizeBytes, String wrappedKey, String keyRef) {
}
