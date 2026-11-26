/**
 * Event and job payload records shared by {@code jdms-core} and {@code jdms-worker}.
 *
 * <p>Records here carry IDs and the tenant only, never document content or sensitive metadata. This module has
 * no logic and no dependencies.
 *
 * <p>Because this package sits under {@code com.jmr.jdms}, Spring Modulith treats it as an application module of
 * {@code jdms-core}. Keep every record in this top-level package: that is the module's public API, so all core
 * modules may use it. Sub-packages would be module-internal and fail {@code ApplicationModules.verify()}.
 */
package com.jmr.jdms.contracts;
