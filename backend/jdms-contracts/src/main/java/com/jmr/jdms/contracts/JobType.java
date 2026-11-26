package com.jmr.jdms.contracts;

/** Kinds of background job (DM {@code job.type}). */
public enum JobType {
    SCAN,
    EXTRACT,
    RENDER,
    OCR,
    INDEX,
    REINDEX_ACL,
    DISPOSE,
    /** Worker → core: the outcome of a processing step, applied by the core (ADR-0014). */
    PROCESSING_RESULT,
    /** Rebuilds a tenant's search index from the database (ADR-0015). */
    REBUILD_INDEX,
    /** Reads an attached file for assisted indexing: antivirus, text, OCR, field suggestions (ADR-0024). */
    CAPTURE,
    /** Worker → core: what a {@code CAPTURE} job found, applied by the core (ADR-0024). */
    CAPTURE_RESULT
}
