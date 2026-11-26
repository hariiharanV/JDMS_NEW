package com.jmr.jdms.platform.content;

/** The blob is missing or cannot be decrypted (wrong tenant, tampered or truncated). */
public class BlobUnreadableException extends RuntimeException {

    public BlobUnreadableException(String message) {
        super(message);
    }

    public BlobUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
