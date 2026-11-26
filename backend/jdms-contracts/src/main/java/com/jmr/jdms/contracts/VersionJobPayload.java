package com.jmr.jdms.contracts;

import java.util.UUID;

/** Payload of jobs that work on one document version (scan, extract, render, OCR). */
public record VersionJobPayload(UUID tenantId, UUID documentId, UUID versionId) {
}
