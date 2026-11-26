package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.Classification;
import java.util.Set;

/** The editable attributes of a document type (everything except ID, cabinet and code). */
public record DocumentTypeAttributes(
        String nameEn,
        String nameAr,
        String category,
        Set<UploadChannel> channels,
        Classification defaultClassification,
        String processKey,
        boolean metaChangeNeedsApproval,
        boolean deleteRequiresApproval,
        boolean declareRecordOnApproval,
        Set<String> requiredStandardFields) {

    public DocumentTypeAttributes {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
        requiredStandardFields = requiredStandardFields == null ? Set.of() : Set.copyOf(requiredStandardFields);
    }
}
