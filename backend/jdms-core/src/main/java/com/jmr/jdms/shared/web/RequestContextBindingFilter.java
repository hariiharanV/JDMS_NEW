package com.jmr.jdms.shared.web;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.tenant.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs inside the security filter chain right after bearer-token authentication. For an authenticated request it
 * binds the tenant for data access ({@link TenantScope}) and adds {@code tenantId} and {@code userId} to the logging
 * context for the rest of the request. Unauthenticated requests pass through unchanged and are rejected later by
 * authorization.
 */
public class RequestContextBindingFilter extends OncePerRequestFilter {

    public static final String MDC_TENANT_ID = "tenantId";
    public static final String MDC_USER_ID = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RequestContext> context = RequestContext.current();
        if (context.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_TENANT_ID, context.get().tenantId().toString());
        MDC.put(MDC_USER_ID, context.get().subjectId().toString());
        ScopedFilterChain.proceed(TenantScope.bind(context.get().tenantId()), chain, request, response);
    }
}
