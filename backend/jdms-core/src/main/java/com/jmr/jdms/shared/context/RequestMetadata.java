package com.jmr.jdms.shared.context;

import java.util.Optional;

/**
 * Transport facts about the current HTTP request, bound by {@code RequestIdFilter} for the whole request,
 * including authentication.
 *
 * @param requestId the request's {@code X-Request-Id}
 * @param clientIp  the caller's address as seen by the server (proxies are resolved by
 *                  {@code server.forward-headers-strategy} when configured)
 */
public record RequestMetadata(String requestId, String clientIp) {

    public static final ScopedValue<RequestMetadata> CURRENT = ScopedValue.newInstance();

    public static Optional<RequestMetadata> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
