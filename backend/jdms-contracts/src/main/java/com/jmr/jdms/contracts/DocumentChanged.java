package com.jmr.jdms.contracts;

import java.util.UUID;

/**
 * Something that the search index shows changed for a document (approval outcome, processing result such as
 * extracted text), beyond {@link DocumentCreated}, {@link VersionCreated} and {@link MetadataChanged}.
 */
public record DocumentChanged(UUID tenantId, UUID documentId) {
}
