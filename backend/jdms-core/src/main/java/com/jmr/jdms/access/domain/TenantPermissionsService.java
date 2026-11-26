package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.AccessPolicy;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.access.api.ResourceRef;
import com.jmr.jdms.access.api.Subject;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.workflow.api.TenantPermissions;
import org.springframework.stereotype.Component;

/**
 * Tenant-wide policy checks for the workflow module (e.g. who may approve committee changes). Not audited: used to
 * filter queues; endpoints audit their own denials.
 */
@Component
class TenantPermissionsService implements TenantPermissions {

    private final AccessPolicy policy;

    TenantPermissionsService(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean allowed(String action) {
        return RequestContext.current().isPresent() && policy.decide(Subject.current(), Action.valueOf(action),
                new ResourceRef.Tenant(RequestContext.require().tenantId())).allowed();
    }
}
