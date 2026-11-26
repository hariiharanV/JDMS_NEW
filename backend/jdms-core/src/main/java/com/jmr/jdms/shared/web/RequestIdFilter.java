package com.jmr.jdms.shared.web;

import com.jmr.jdms.shared.context.RequestMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a request ID, exposes it as the {@code X-Request-Id} response header, puts it in the logging
 * context ({@code requestId}) and binds {@link RequestMetadata} for the duration of the request.
 *
 * <p>A caller-supplied {@code X-Request-Id} is reused only when it is a short token of safe characters, so it can
 * never inject content into logs or headers; anything else is replaced by a new random UUID.
 *
 * <p>This filter runs first and owns the logging context: it also clears {@code tenantId} and {@code userId}, which
 * {@link RequestContextBindingFilter} adds after authentication, so the completion line still carries them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            ScopedFilterChain.proceed(
                    ScopedValue.where(RequestMetadata.CURRENT, new RequestMetadata(requestId, request.getRemoteAddr())),
                    chain, request, response);
        } finally {
            // Path only: query strings can carry business keys (CIF, account numbers) and are never logged
            log.debug("Request completed method={} path={} status={} durationMs={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(MDC_KEY);
            MDC.remove(RequestContextBindingFilter.MDC_TENANT_ID);
            MDC.remove(RequestContextBindingFilter.MDC_USER_ID);
        }
    }

    static String resolve(String supplied) {
        if (supplied != null && SAFE_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
