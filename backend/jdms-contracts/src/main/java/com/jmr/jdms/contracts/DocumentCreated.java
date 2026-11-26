package com.jmr.jdms.contracts;

import java.util.UUID;

/** A document and its first version were committed. */
public record DocumentCreated(UUID tenantId, UUID documentId, UUID versionId) {
}
