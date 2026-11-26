package com.jmr.jdms.document.domain;

/** Document lifecycle (spec §5). */
public enum DocumentStatus {
    DRAFT,
    PENDING_APPROVAL,
    ACTIVE,
    REJECTED,
    DELETED,
    DISPOSED
}
