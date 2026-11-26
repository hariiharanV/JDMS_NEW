package com.jmr.jdms.contracts;

import java.util.UUID;

/** Payload of jobs about one document ({@code INDEX}); {@code documentId} is null for tenant-wide jobs. */
public record DocumentJobPayload(UUID tenantId, UUID documentId) {
}
