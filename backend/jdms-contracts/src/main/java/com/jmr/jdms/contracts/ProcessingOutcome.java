package com.jmr.jdms.contracts;

/** Result of one processing step. */
public enum ProcessingOutcome {
    /** Antivirus found nothing. */
    CLEAN,
    /** Antivirus found malware: the version is quarantined and processing stops. */
    INFECTED,
    /** The step finished (possibly producing nothing, e.g. no text). */
    DONE,
    /** The step failed permanently or its retries were exhausted; processing stops. */
    FAILED
}
