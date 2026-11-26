package com.jmr.jdms.shared.error;

/** Stable error codes shared across modules. Module-specific codes live with their module. */
public final class ErrorCodes {

    public static final String AUTH_UNAUTHENTICATED = "AUTH_UNAUTHENTICATED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String PRECONDITION_REQUIRED = "PRECONDITION_REQUIRED";
    public static final String PRECONDITION_FAILED = "PRECONDITION_FAILED";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private ErrorCodes() {
    }
}
