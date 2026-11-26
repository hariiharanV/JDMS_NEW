package com.jmr.jdms.shared.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/** 422 with the list of violations in the problem's {@code errors} member. */
public class ValidationFailedException extends ApiException {

    private final List<Violation> violations;

    public ValidationFailedException(String detail, List<Violation> violations) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION_FAILED, detail);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    /** Throws when there are violations. */
    public static void throwIfAny(String detail, List<Violation> violations) {
        if (!violations.isEmpty()) {
            throw new ValidationFailedException(detail, violations);
        }
    }
}
