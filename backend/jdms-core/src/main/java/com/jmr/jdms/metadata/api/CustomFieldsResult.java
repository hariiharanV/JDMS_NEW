package com.jmr.jdms.metadata.api;

import com.jmr.jdms.shared.error.Violation;
import java.util.List;
import java.util.Map;

/**
 * Outcome of validating a document's custom fields.
 *
 * @param values     normalised values in field display order (defaults applied; NUMBER as {@code BigDecimal},
 *                   DATE as {@code yyyy-MM-dd}, BOOLEAN as {@code Boolean}, LOV as the value code); empty when invalid
 * @param violations one entry per invalid field; messages never contain the submitted value
 */
public record CustomFieldsResult(Map<String, Object> values, List<Violation> violations) {

    public CustomFieldsResult {
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        violations = List.copyOf(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }
}
