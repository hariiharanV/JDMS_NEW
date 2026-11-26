package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.shared.context.RequestContext;
import org.springframework.stereotype.Component;

/**
 * Tenant-wide checks for {@code @PreAuthorize} on controllers: {@code @PreAuthorize("@access.can('ADMIN_METADATA')")}.
 * Denials are audited. Document-level checks happen in services, where the document is loaded.
 */
@Component("access")
public class AccessExpressions {

    private final AccessPolicy policy;

    AccessExpressions(AccessPolicy policy) {
        this.policy = policy;
    }

    public boolean can(String action) {
        return RequestContext.current().map(context -> policy.check(Action.valueOf(action),
                new ResourceRef.Tenant(context.tenantId())).allowed()).orElse(false);
    }
}
