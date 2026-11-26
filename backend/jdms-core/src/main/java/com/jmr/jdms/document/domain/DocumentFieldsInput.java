package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.Classification;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Document metadata as sent by clients (upload {@code metadata} part, and the state a PATCH merge-patch applies to).
 * {@code cabinetCode} and {@code documentTypeCode} are fixed after upload.
 */
public record DocumentFieldsInput(
        String cabinetCode,
        String documentTypeCode,
        String title,
        Classification classification,
        String branchCode,
        UUID folderId,
        String cif,
        String customerName,
        String accountNo,
        String collateralCode,
        String contractRef,
        String transactionRef,
        String applicationRef,
        LocalDate expiryDate,
        String physicalLocation,
        String physicalRef,
        String externalRef,
        Map<String, Object> customFields) {
}
