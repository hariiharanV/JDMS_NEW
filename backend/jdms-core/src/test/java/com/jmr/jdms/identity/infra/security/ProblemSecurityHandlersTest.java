package com.jmr.jdms.identity.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.shared.web.ProblemResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

/** B-13: a caller that cannot be identified because the database is unavailable gets a retryable 503, not 401. */
class ProblemSecurityHandlersTest {

    final ProblemSecurityHandlers handlers = new ProblemSecurityHandlers(
            new ProblemResponseWriter(JsonMapper.builder().build()));

    @Test
    void databaseUnavailableWhileIdentifyingTheCallerIs503() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.commence(new MockHttpServletRequest(), response, new AuthenticationServiceException("sync failed",
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction")));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    }

    @Test
    void anInvalidTokenIsStill401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlers.commence(new MockHttpServletRequest(), response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"AUTH_UNAUTHENTICATED\"");
    }
}
