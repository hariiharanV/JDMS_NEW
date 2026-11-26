package com.jmr.jdms.platform.content;

/** The plaintext was empty; nothing was stored. */
public class EmptyBlobException extends RuntimeException {

    public EmptyBlobException(String message) {
        super(message);
    }

    public EmptyBlobException(String message, Throwable cause) {
        super(message, cause);
    }
}
