package com.jmr.jdms.contracts;

import java.util.UUID;

/**
 * Payload of the worker's EXTRACT, OCR and RENDER jobs. {@code detectedMimeType} is the type detected from the
 * content (never the client-supplied one), known after EXTRACT.
 */
public record ProcessingStepPayload(UUID tenantId, UUID documentId, UUID versionId, String detectedMimeType) {
}
