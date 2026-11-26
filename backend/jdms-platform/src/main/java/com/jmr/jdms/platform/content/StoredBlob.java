package com.jmr.jdms.platform.content;

/** Where and how a blob was stored: key, SHA-256 and size of the plaintext, wrapped data key and its key version. */
public record StoredBlob(String blobKey, String sha256, long sizeBytes, String wrappedKey, String keyRef) {
}
