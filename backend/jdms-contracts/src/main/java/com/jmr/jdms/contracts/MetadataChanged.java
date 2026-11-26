package com.jmr.jdms.contracts;

import java.util.UUID;

/** A document's metadata changed (search re-index). */
public record MetadataChanged(UUID tenantId, UUID documentId) {
}
