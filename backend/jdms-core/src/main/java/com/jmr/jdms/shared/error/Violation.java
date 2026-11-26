package com.jmr.jdms.shared.error;

/**
 * One invalid input. {@code message} must never echo the submitted value: values can be sensitive (CLAUDE.md
 * logging rules).
 */
public record Violation(String field, String code, String message) {
}
