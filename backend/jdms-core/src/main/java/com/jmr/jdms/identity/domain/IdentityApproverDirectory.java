package com.jmr.jdms.identity.domain;

import com.jmr.jdms.workflow.api.ApproverDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Committee membership checks against the tenant's user profiles. */
@Component
class IdentityApproverDirectory implements ApproverDirectory {

    private final UserProfileRepository profiles;
    private final UserRoles roles;

    IdentityApproverDirectory(UserProfileRepository profiles, UserRoles roles) {
        this.profiles = profiles;
        this.roles = roles;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveUser(UUID userId) {
        return profiles.findById(userId).map(profile -> profile.getStatus() == UserStatus.ACTIVE).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<Candidate> activeUsers() {
        return profiles.findAll(org.springframework.data.domain.Sort.by("username")).stream()
                .filter(profile -> profile.getStatus() == UserStatus.ACTIVE
                        && profile.getPrincipalType() == com.jmr.jdms.shared.context.PrincipalType.USER)
                .map(profile -> new Candidate(profile.getId(), profile.getUsername(), profile.getDisplayName(),
                        roles.of(profile.getId())))
                .toList();
    }
}
