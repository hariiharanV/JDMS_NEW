package com.jmr.jdms.worker.capture;

import java.util.Map;

/**
 * A custom field of the document type a capture is for, as the suggester needs it (ADR-0024): its English label and
 * key to look for in the text, and its type to read the value as.
 *
 * @param dataType  {@code TEXT}, {@code NUMBER}, {@code DATE}, {@code BOOLEAN} or {@code LOV}
 * @param maxLength longest TEXT value accepted; null when the field has no limit
 * @param lovValues active list values, code to English label (LOV fields only)
 */
public record FieldHint(String key, String labelEn, String dataType, Integer maxLength,
        Map<String, String> lovValues) {

    public FieldHint {
        lovValues = lovValues == null ? Map.of() : Map.copyOf(lovValues);
    }
}
