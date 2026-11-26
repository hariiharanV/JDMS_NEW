package com.jmr.jdms.shared.error;

import org.springframework.http.HttpStatus;

/**
 * A failure reported to the caller as {@code application/problem+json} with a stable {@code code} (RFC 9457).
 * The detail must be safe to show to the caller: no internal identifiers of other tenants, no stack traces.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }

    /** 412: the resource changed since the caller read it (optimistic concurrency, If-Match). */
    public static ApiException staleVersion() {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, ErrorCodes.PRECONDITION_FAILED,
                "The resource was changed by someone else. Reload it and try again.");
    }
}
