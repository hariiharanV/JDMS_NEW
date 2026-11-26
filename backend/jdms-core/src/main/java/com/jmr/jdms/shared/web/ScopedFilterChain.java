package com.jmr.jdms.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/** Runs the rest of a servlet filter chain with scoped values bound, keeping the chain's checked exceptions. */
final class ScopedFilterChain {

    private ScopedFilterChain() {
    }

    static void proceed(ScopedValue.Carrier bindings, FilterChain chain, ServletRequest request,
            ServletResponse response) throws IOException, ServletException {
        try {
            bindings.call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
