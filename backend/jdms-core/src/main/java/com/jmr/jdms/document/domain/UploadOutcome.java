package com.jmr.jdms.document.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Result of an idempotent write: status and body as first returned (replays return the stored copy). */
public record UploadOutcome(int status, UUID documentId, JsonNode body, boolean replayed) {
}
