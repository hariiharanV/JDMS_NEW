package com.jmr.jdms.shared.web;

import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ErrorCodes;
import org.springframework.http.HttpStatus;

/** Optimistic concurrency for updates ({@code docs/04-api-outline.md}): ETag = the row's {@code version_lock}. */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * The version in an {@code If-Match} header. Missing → 428 {@code PRECONDITION_REQUIRED}; unparseable → 412
     * {@code PRECONDITION_FAILED}.
     */
    public static long requiredVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, ErrorCodes.PRECONDITION_REQUIRED,
                    "If-Match with the resource's ETag is required.");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw ApiException.staleVersion();
        }
    }
}
