package com.jmr.jdms.metadata.api;

import com.jmr.jdms.shared.Classification;
import java.util.Set;
import java.util.UUID;

/** A document type and its cabinet, as needed to store and govern documents. */
public record DocumentTypeInfo(
        UUID id,
        String code,
        String category,
        boolean active,
        UUID cabinetId,
        String cabinetCode,
        boolean cabinetActive,
        int maxFileSizeMb,
        long quotaMb,
        Set<String> channels,
        Classification defaultClassification,
        String processKey,
        boolean metaChangeNeedsApproval,
        boolean deleteRequiresApproval,
        boolean declareRecordOnApproval,
        Set<String> requiredStandardFields,
        Set<String> sensitiveFields,
        UUID retentionPolicyId) {

    public static final String CHANNEL_UI = "UI";
    public static final String CHANNEL_INTEGRATION = "INTEGRATION";

    public boolean requiresApproval() {
        return processKey != null;
    }
}
