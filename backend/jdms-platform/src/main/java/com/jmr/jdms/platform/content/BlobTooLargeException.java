package com.jmr.jdms.platform.content;

/** The plaintext exceeded the allowed size; nothing was stored. */
public class BlobTooLargeException extends RuntimeException {

    public BlobTooLargeException(String message) {
        super(message);
    }

    public BlobTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
