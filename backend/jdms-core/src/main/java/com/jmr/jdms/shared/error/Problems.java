package com.jmr.jdms.shared.error;

import com.jmr.jdms.shared.context.RequestMetadata;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem details with the JDMS Next extension members {@code code} and {@code requestId}. */
public final class Problems {

    private static final String TYPE_BASE = "https://jdms.jmr.com/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        RequestMetadata.current().ifPresent(metadata -> problem.setProperty("requestId", metadata.requestId()));
        return problem;
    }
}
