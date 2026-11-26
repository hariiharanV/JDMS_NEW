/**
 * Cross-cutting primitives only: IDs, request and tenant context, errors, web plumbing. Open to every module; holds no business logic.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.jmr.jdms.shared;

import org.springframework.modulith.ApplicationModule;
