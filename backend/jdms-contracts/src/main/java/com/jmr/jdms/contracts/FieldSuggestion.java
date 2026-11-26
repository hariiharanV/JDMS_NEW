package com.jmr.jdms.contracts;

/**
 * A value read from an attached file for one upload field (ADR-0024). Stored by the worker in the capture's encrypted
 * suggestions blob and returned by the core to the user who attached the file, who confirms or corrects it.
 *
 * @param field      the upload field: a standard field as the API names it ({@code cif}, {@code accountNo},
 *                   {@code expiryDate}, ...) or {@code customFields.<key>}
 * @param value      the value as the upload API expects it: text, a number as a plain decimal, a date as
 *                   {@code yyyy-MM-dd}, {@code true}/{@code false}, or a list value's code
 * @param confidence {@code HIGH} when a known label and a value of the right form were found together on one line;
 *                   {@code MEDIUM} for a value on the next line, a generic label or conflicting candidates
 * @param label      the label the value was found next to, as written in the document (shown as the evidence)
 */
public record FieldSuggestion(String field, String value, Confidence confidence, String label) {

    public enum Confidence {
        HIGH, MEDIUM
    }
}
