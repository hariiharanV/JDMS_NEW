package com.jmr.jdms.document.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.jmr.jdms.shared.Classification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A document in API responses: metadata, status, lock and current version. */
public record DocumentView(
        UUID id,
        String cabinetCode,
        String documentTypeCode,
        UUID folderId,
        String title,
        DocumentStatus status,
        Classification classification,
        String branchCode,
        @JsonUnwrapped StandardMetadata standardFields,
        Map<String, Object> customFields,
        VersionView currentVersion,
        UUID checkedOutBy,
        Instant checkedOutAt,
        String sourceSystem,
        String externalRef,
        boolean onLegalHold,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        long version) {
}
