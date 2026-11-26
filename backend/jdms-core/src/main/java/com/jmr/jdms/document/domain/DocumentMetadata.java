package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.Classification;
import java.util.UUID;

/** A document's editable metadata after validation; custom fields as canonical JSON. */
public record DocumentMetadata(
        String title,
        UUID folderId,
        Classification classification,
        UUID branchId,
        StandardMetadata standard,
        String customFieldsJson,
        String externalRef) {
}
