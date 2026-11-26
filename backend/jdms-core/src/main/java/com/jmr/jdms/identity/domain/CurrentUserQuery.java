package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import com.jmr.jdms.tenant.api.TenantDirectory;
import com.jmr.jdms.tenant.api.TenantInfo;
import com.jmr.jdms.tenant.api.TenantLanguages;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserQuery {

    private final UserProfileRepository profiles;
    private final BranchDirectory branchDirectory;
    private final TenantDirectory tenants;

    CurrentUserQuery(UserProfileRepository profiles, BranchDirectory branchDirectory, TenantDirectory tenants) {
        this.profiles = profiles;
        this.branchDirectory = branchDirectory;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public CurrentUserView describe(RequestContext context) {
        String displayName = profiles.findById(context.subjectId()).map(UserProfile::getDisplayName).orElse(null);
        return new CurrentUserView(context.tenantCode(), context.subjectId(), context.username(), displayName,
                context.principalType(), context.roles().stream().sorted().toList(),
                context.delegatedRoles().stream().sorted().toList(), context.branchScope().all(),
                branchDirectory.findByIds(context.branchScope().branchIds()).stream().map(BranchInfo::code).sorted()
                        .toList(),
                context.clearance(), tenants.findById(context.tenantId()).map(TenantInfo::languages)
                        .orElse(List.of(TenantLanguages.ENGLISH)),
                context.requestId());
    }
}
