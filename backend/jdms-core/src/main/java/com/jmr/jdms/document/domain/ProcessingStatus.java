package com.jmr.jdms.document.domain;

/** Worker pipeline state (spec §5). */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED,
    QUARANTINED
}
