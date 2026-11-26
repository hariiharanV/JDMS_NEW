package com.jmr.jdms.document.domain;

/** Where the version's blob stands (spec §5). */
public enum StorageStatus {
    STORED,
    MISSING,
    QUARANTINED,
    DESTROYED
}
