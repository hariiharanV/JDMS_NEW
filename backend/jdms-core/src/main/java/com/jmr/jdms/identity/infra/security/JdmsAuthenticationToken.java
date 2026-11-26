package com.jmr.jdms.identity.infra.security;

import com.jmr.jdms.shared.context.RequestContext;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** An authenticated request: the principal is the {@link RequestContext}, the credentials the validated token. */
public class JdmsAuthenticationToken extends AbstractAuthenticationToken {

    private final RequestContext context;
    private final transient Jwt jwt;

    JdmsAuthenticationToken(RequestContext context, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.context = context;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public RequestContext getPrincipal() {
        return context;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return context.subjectId().toString();
    }
}
