package com.jmr.jdms.identity.infra.security;

import com.jmr.jdms.shared.error.ErrorCodes;
import com.jmr.jdms.shared.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 401 and 403 from the security filter chain as problem+json, with a standard {@code WWW-Authenticate}; 503 when the
 * caller could not be identified because the database is unavailable.
 */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemResponseWriter writer;

    ProblemSecurityHandlers(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        if (databaseUnavailable(exception)) {
            // the token may be fine: its user profile could not be read (overload, outage). Retryable (B-13).
            response.setHeader(HttpHeaders.RETRY_AFTER, "5");
            writer.write(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVICE_UNAVAILABLE,
                    "The service is busy or temporarily unavailable. Retry shortly.");
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, exception instanceof OAuth2AuthenticationException
                ? "Bearer error=\"invalid_token\"" : "Bearer");
        writer.write(response, HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_UNAUTHENTICATED,
                "A valid access token is required.");
    }

    private static boolean databaseUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException || cause instanceof DataAccessResourceFailureException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        writer.write(response, HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "You are not allowed to perform this action.");
    }
}
