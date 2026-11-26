package com.jmr.jdms.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every error to {@code application/problem+json} with a stable {@code code} and the {@code requestId}
 * (CLAUDE.md coding conventions). Unexpected exceptions are logged with the request ID and returned without detail.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception) {
        ProblemDetail problem = Problems.of(exception.status(), exception.code(), exception.getMessage());
        if (exception instanceof ValidationFailedException validation) {
            problem.setProperty("errors", validation.violations());
        }
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "You are not allowed to perform this action.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraintViolation(ConstraintViolationException exception) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "The request is invalid.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimisticLock(ObjectOptimisticLockingFailureException exception) {
        return problem(HttpStatus.PRECONDITION_FAILED, ErrorCodes.PRECONDITION_FAILED,
                "The resource was changed by someone else. Reload it and try again.");
    }

    /** A database constraint caught a conflict the application checks missed (e.g. a race): never a 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> dataIntegrity(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Data integrity conflict on {} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                "The change conflicts with the current data. Reload and try again.");
    }

    /**
     * The database is unreachable or every connection is busy (overload): retryable, so 503 with Retry-After rather
     * than 500 (B-13). Clients retry with the same Idempotency-Key.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception exception, HttpServletRequest request) {
        log.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVICE_UNAVAILABLE,
                        "The service is busy or temporarily unavailable. Retry shortly."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                "The request is invalid.");
        problem.setProperty("errors", exception.getFieldErrors().stream()
                .map(error -> java.util.Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    /** Framework errors (bad JSON, unsupported media type, missing parameter...) keep their status, gain a code. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        String detail = body instanceof ErrorResponse response ? response.getBody().getDetail()
                : body instanceof ProblemDetail problem ? problem.getDetail() : null;
        String code = status.value() == 404 ? ErrorCodes.NOT_FOUND
                : status.is4xxClientError() ? ErrorCodes.VALIDATION_FAILED : ErrorCodes.INTERNAL_ERROR;
        return new ResponseEntity<>(Problems.of(status, code, detail), headers, status);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail) {
        return ResponseEntity.status(status).body(Problems.of(status, code, detail));
    }
}
