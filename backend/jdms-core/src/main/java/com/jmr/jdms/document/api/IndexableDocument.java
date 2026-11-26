package com.jmr.jdms.document.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A document as the search index needs it: metadata, the attributes access decisions depend on (cabinet, type,
 * folder, branch, classification), and the state of its current version. Custom fields are all values; the indexer
 * keeps only searchable, non-sensitive ones.
 *
 * @param indexVersion grows with every change that alters the indexed form (stale writes are rejected)
 * @param hasText      the current version has extracted or OCR text
 */
public record IndexableDocument(UUID id, UUID cabinetId, String cabinetCode, UUID documentTypeId,
        String documentTypeCode, UUID folderId, String title, String status, String classification, UUID branchId,
        String branchCode, String cif, String customerName, String accountNo, String collateralCode,
        String contractRef, String transactionRef, String applicationRef, String externalRef, LocalDate expiryDate,
        Map<String, Object> customFields, Instant createdAt, Instant updatedAt, UUID currentVersionId,
        String currentVersion, String processingStatus, boolean hasText, boolean onLegalHold, long indexVersion) {
}
