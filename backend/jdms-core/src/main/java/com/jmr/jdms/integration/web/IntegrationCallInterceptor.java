package com.jmr.jdms.integration.web;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.shared.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * For {@code /integration/**} (spec §4.10, API outline: stricter rate limits, every call audited with the client
 * ID): a token bucket per client ({@code jdms.integration.rate-limit.*}; 429 {@code RATE_LIMITED} with
 * {@code Retry-After}), and an {@code INTEGRATION_CALL} audit event per request with method, route and status.
 * Buckets are per node; a cluster divides the limit by its size.
 */
@Component
class IntegrationCallInterceptor implements HandlerInterceptor {

    static final String RATE_LIMITED = "RATE_LIMITED";

    private final AuditTrail auditTrail;
    private final ProblemResponseWriter problems;
    private final double perSecond;
    private final double burst;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    IntegrationCallInterceptor(AuditTrail auditTrail, ProblemResponseWriter problems,
            @Value("${jdms.integration.rate-limit.requests-per-second:20}") double perSecond,
            @Value("${jdms.integration.rate-limit.burst:40}") double burst) {
        this.auditTrail = auditTrail;
        this.problems = problems;
        this.perSecond = perSecond;
        this.burst = burst;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Optional<RequestContext> caller = RequestContext.current();
        if (caller.isEmpty()) {
            return true; // unauthenticated requests never get here; security answers 401 first
        }
        Bucket bucket = buckets.computeIfAbsent(caller.get().tenantId() + "/" + caller.get().subjectId(),
                key -> new Bucket(burst));
        Duration wait = bucket.take(perSecond, burst);
        if (!wait.isZero()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (wait.toMillis() + 999) / 1000)));
            problems.write(response, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED,
                    "Too many requests from this client; retry later.");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        RequestContext.current().ifPresent(caller -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", request.getMethod());
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            details.put("route", route == null ? "unmatched" : route.toString());
            details.put("status", response.getStatus());
            details.put("clientId", caller.username());
            int status = response.getStatus();
            AuditOutcome outcome = status < 400 ? AuditOutcome.SUCCESS
                    : status == 401 || status == 403 ? AuditOutcome.DENIED : AuditOutcome.ERROR;
            TenantScope.run(caller.tenantId(), () -> auditTrail.record(new AuditEntry("INTEGRATION_CALL",
                    "INTEGRATION", caller.subjectId().toString(), outcome, details, null, null)));
        });
    }

    /** A token bucket refilled at {@code perSecond}, holding at most {@code burst} tokens. */
    static final class Bucket {

        private double tokens;
        private long refilledAt = System.nanoTime();

        Bucket(double tokens) {
            this.tokens = tokens;
        }

        synchronized Duration take(double perSecond, double burst) {
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - refilledAt) / 1e9 * perSecond);
            refilledAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return Duration.ZERO;
            }
            return Duration.ofMillis((long) Math.ceil((1 - tokens) / perSecond * 1000));
        }
    }
}
