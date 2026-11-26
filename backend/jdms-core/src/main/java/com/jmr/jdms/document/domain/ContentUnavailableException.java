package com.jmr.jdms.document.domain;

/** Stored content is missing or failed integrity checks (decryption, authentication or SHA-256). */
public class ContentUnavailableException extends RuntimeException {

    public ContentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
