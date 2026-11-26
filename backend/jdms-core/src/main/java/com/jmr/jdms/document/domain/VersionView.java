package com.jmr.jdms.document.domain;

import java.time.Instant;
import java.util.UUID;

/** A version in API responses (never the entity). */
public record VersionView(
        UUID id,
        String number,
        ApprovalStatus approvalStatus,
        long sizeBytes,
        String mimeType,
        String detectedMimeType,
        Integer pageCount,
        String originalFilename,
        String sha256,
        StorageStatus storageStatus,
        ProcessingStatus processingStatus,
        IndexStatus indexStatus,
        String comment,
        Instant createdAt,
        String createdBy) {

    static VersionView of(DocumentVersion version) {
        return new VersionView(version.getId(), version.number(), version.getApprovalStatus(), version.getSizeBytes(),
                version.getMimeType(), version.getDetectedMimeType(), version.getPageCount(),
                version.getOriginalFilename(), version.getSha256(), version.getStorageStatus(),
                version.getProcessingStatus(), version.getIndexStatus(), version.getVersionComment(),
                version.getCreatedAt(), version.getCreatedBy());
    }
}
