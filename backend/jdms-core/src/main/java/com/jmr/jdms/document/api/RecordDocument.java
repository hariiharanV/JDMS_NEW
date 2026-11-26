package com.jmr.jdms.document.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A document as retention and disposition need it. */
public record RecordDocument(UUID id, UUID documentTypeId, String status, Instant createdAt, LocalDate expiryDate,
        String accountNo, boolean onLegalHold, boolean declaredRecord) {
}
