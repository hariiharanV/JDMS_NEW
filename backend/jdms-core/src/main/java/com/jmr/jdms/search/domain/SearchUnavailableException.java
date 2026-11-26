package com.jmr.jdms.search.domain;

/** The search engine could not be reached or refused the request. */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
