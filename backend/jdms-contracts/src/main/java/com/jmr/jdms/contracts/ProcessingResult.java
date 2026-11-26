package com.jmr.jdms.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code PROCESSING_RESULT} jobs: what a processing step produced. The core applies it (status,
 * renditions, audit, notifications); applying the same result twice changes nothing.
 *
 * @param detail           virus signature (INFECTED) or failure reason (FAILED); never document content
 * @param detectedMimeType content type detected by the worker, if known
 * @param pageCount        pages of the PDF rendition or original, if known
 * @param ocr              the TEXT rendition comes from OCR
 * @param last             processing of the version is complete with this step
 */
public record ProcessingResult(UUID tenantId, UUID documentId, UUID versionId, ProcessingStep step,
        ProcessingOutcome outcome, String detail, String detectedMimeType, Integer pageCount,
        List<RenditionBlob> renditions, boolean ocr, boolean last) {

    public ProcessingResult {
        renditions = renditions == null ? List.of() : List.copyOf(renditions);
    }
}
