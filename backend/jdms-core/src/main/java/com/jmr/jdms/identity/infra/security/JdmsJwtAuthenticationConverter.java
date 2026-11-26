package com.jmr.jdms.identity.infra.security;

import com.jmr.jdms.identity.domain.IdentityClaims;
import com.jmr.jdms.identity.domain.IdentityConflictException;
import com.jmr.jdms.identity.domain.SyncedProfile;
import com.jmr.jdms.identity.domain.UserProfileSynchronizer;
import com.jmr.jdms.identity.domain.UserStatus;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.context.RequestMetadata;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import com.jmr.jdms.tenant.api.TenantInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Turns a validated access token into the request's {@link RequestContext}: the {@code tenant} claim must name an
 * existing, active tenant (otherwise 401), and the caller's {@code user_profile} is created or refreshed in that
 * tenant. Branch scope and clearance come from the profile.
 */
@Component
class JdmsJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(JdmsJwtAuthenticationConverter.class);

    private final TenantDirectory tenants;
    private final UserProfileSynchronizer synchronizer;

    JdmsJwtAuthenticationConverter(TenantDirectory tenants, UserProfileSynchronizer synchronizer) {
        this.tenants = tenants;
        this.synchronizer = synchronizer;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        IdentityClaims claims = JwtIdentityClaimsReader.read(jwt);
        TenantInfo tenant = tenants.findActiveByCode(claims.tenantCode())
                .orElseThrow(() -> new InvalidBearerTokenException("The access token is not valid for this service."));

        SyncedProfile profile = TenantScope.call(tenant.id(), () -> synchronize(claims));
        if (profile.status() != UserStatus.ACTIVE) {
            throw new InvalidBearerTokenException("The access token is not valid for this service.");
        }

        Set<String> effectiveRoles = new HashSet<>(claims.roles());
        effectiveRoles.addAll(profile.delegatedRoles());
        RequestContext context = new RequestContext(tenant.id(), tenant.code(), claims.subject(), claims.username(),
                claims.principalType(), effectiveRoles, profile.delegatedRoles(), profile.branchScope(),
                profile.clearance(), RequestMetadata.current().map(RequestMetadata::requestId).orElse(null));
        List<SimpleGrantedAuthority> authorities = effectiveRoles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new JdmsAuthenticationToken(context, jwt, authorities);
    }

    /** Two first requests of a new user can race to create the profile; the loser re-reads it. */
    private SyncedProfile synchronize(IdentityClaims claims) {
        try {
            try {
                return synchronizer.synchronize(claims);
            } catch (DataIntegrityViolationException concurrentFirstLogin) {
                return synchronizer.synchronize(claims);
            }
        } catch (IdentityConflictException e) {
            log.warn("Login refused: username {} already belongs to another profile (the identity-provider user was "
                    + "recreated). An administrator must disable or rename the old profile.", claims.username());
            throw new InvalidBearerTokenException("The access token is not valid for this service.");
        }
    }
}
