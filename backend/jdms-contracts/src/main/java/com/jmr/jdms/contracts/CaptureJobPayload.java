package com.jmr.jdms.contracts;

import java.util.UUID;

/** Payload of {@code CAPTURE} jobs: the capture to read (ADR-0024). IDs only. */
public record CaptureJobPayload(UUID tenantId, UUID captureId) {
}
