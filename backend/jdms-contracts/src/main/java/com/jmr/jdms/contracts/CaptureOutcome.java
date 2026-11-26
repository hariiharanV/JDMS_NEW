package com.jmr.jdms.contracts;

/** How reading a capture ended (ADR-0024). */
public enum CaptureOutcome {
    /** Read; there may be no suggestions. */
    DONE,
    /** Antivirus found malware: nothing is suggested and the file is deleted. */
    INFECTED,
    /** The file could not be read (too large, malformed, retries exhausted). */
    FAILED
}
