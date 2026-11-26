package com.jmr.jdms.document.domain;

/** Where reading an attached file for assisted indexing stands (ADR-0024). */
public enum CaptureStatus {
    /** The worker is reading the file. */
    PENDING,
    /** Read; suggestions (possibly none) are available. */
    DONE,
    /** Antivirus found malware; the file was deleted. */
    INFECTED,
    /** The file could not be read. */
    FAILED
}
