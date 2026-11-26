package com.jmr.jdms.document.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores normalised custom fields as canonical JSON. Decimals stay {@code BigDecimal} both ways, so amounts never
 * pass through binary floating point.
 */
public final class CustomFieldsCodec {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private CustomFieldsCodec() {
    }

    public static String write(Map<String, Object> values) {
        return JSON.writeValueAsString(values == null ? Map.of() : values);
    }

    public static Map<String, Object> read(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, MAP);
    }
}
