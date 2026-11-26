package com.jmr.jdms.contracts;

import java.util.UUID;

/**
 * Payload of {@code CAPTURE_RESULT} jobs: what the worker found in a capture (ADR-0024). The suggested values are not
 * in the payload (job payloads hold IDs, never content): they are in {@code suggestions}, an encrypted blob holding
 * a JSON array of {@link FieldSuggestion}. Applying the same result twice changes nothing.
 *
 * @param detail           virus signature (INFECTED) or failure reason (FAILED); never document content
 * @param ocr              the text came from OCR
 * @param suggestions      the suggestions blob; null when nothing was found
 * @param suggestionCount  number of suggestions in the blob
 */
public record CaptureResult(UUID tenantId, UUID captureId, CaptureOutcome outcome, String detail,
        String detectedMimeType, boolean ocr, RenditionBlob suggestions, int suggestionCount) {
}
