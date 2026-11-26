package com.jmr.jdms.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

/** B-13: no connection to the database (outage or every connection busy) is a retryable 503, not a 500. */
class ProblemDetailsAdviceTest {

    final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void databaseUnavailableIs503WithRetryAfter() {
        for (Exception unavailable : new Exception[] {new CannotCreateTransactionException("pool exhausted"),
                new CannotGetJdbcConnectionException("refused")}) {
            ResponseEntity<ProblemDetail> response = advice.databaseUnavailable(unavailable,
                    new MockHttpServletRequest("POST", "/api/v1/documents"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(response.getBody().getProperties()).containsEntry("code", ErrorCodes.SERVICE_UNAVAILABLE);
        }
    }
}
