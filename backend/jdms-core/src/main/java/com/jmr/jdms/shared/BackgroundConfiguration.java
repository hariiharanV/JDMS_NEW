package com.jmr.jdms.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Asynchronous event listeners (outbox deliveries) and scheduled jobs. Background work has no request context: it
 * binds the tenant from its payload with {@code TenantScope} before opening a transaction.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
class BackgroundConfiguration {
}
