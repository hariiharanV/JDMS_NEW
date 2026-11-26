package com.jmr.jdms.platform.content;

/** The object store could not be reached or failed (timeouts, 5xx): retrying later may succeed. */
public class BlobStoreUnavailableException extends RuntimeException {

    public BlobStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
