package com.jmr.jdms.platform.jobs;

/**
 * A failure that retrying cannot fix (malformed or oversized input): the job becomes {@code FAILED} at once. The
 * message is stored as the job's {@code last_error}, so it must never contain content, metadata values or secrets.
 */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
