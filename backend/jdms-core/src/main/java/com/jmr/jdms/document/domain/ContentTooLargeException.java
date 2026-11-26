package com.jmr.jdms.document.domain;

/** Content exceeds the allowed size; nothing was stored. */
public class ContentTooLargeException extends RuntimeException {

    public ContentTooLargeException(String message) {
        super(message);
    }
}
