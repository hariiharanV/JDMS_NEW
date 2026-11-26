package com.jmr.jdms.contracts;

/**
 * A rendition the worker stored as a new encrypted blob (DM {@code rendition}).
 *
 * @param kind     {@code PDF}, {@code THUMBNAIL} or {@code TEXT}
 * @param mimeType of the rendition ({@code application/pdf}, {@code image/png}, {@code text/plain})
 */
public record RenditionBlob(String kind, String mimeType, String blobKey, String sha256, long sizeBytes,
        String wrappedKey, String keyRef) {
}
