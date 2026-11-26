/**
 * Infrastructure shared by {@code jdms-core} and {@code jdms-worker} (ADR-0014): encrypted blob storage, the job
 * queue, IDs. No domain logic.
 *
 * <p>The package sits under {@code com.jmr.jdms}, so Spring Modulith sees it as a module of the core; it is an open
 * module: every core module may use any of its packages, and it depends on none of them.
 */
@ApplicationModule(displayName = "Platform", type = ApplicationModule.Type.OPEN)
package com.jmr.jdms.platform;

import org.springframework.modulith.ApplicationModule;
