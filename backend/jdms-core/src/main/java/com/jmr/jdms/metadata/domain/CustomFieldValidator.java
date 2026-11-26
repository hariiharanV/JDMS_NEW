package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.CustomFieldsResult;
import com.jmr.jdms.shared.error.Violation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The custom-field validation engine (B-02): checks a document's custom field values against its type's field
 * definitions and normalises them. Pure: LOV values are supplied by the caller.
 *
 * <p>Messages never contain submitted values, which may be sensitive.
 */
public final class CustomFieldValidator {

    public static final String FIELD_UNKNOWN = "FIELD_UNKNOWN";
    public static final String FIELD_REQUIRED = "FIELD_REQUIRED";
    public static final String FIELD_TYPE = "FIELD_TYPE";
    public static final String FIELD_TOO_LONG = "FIELD_TOO_LONG";
    public static final String FIELD_LOV_VALUE = "FIELD_LOV_VALUE";
    public static final String FIELD_INVALID_CHARACTERS = "FIELD_INVALID_CHARACTERS";

    /** NUMBER values: at most 38 significant digits (portable to Oracle NUMBER). */
    static final int MAX_PRECISION = 38;

    /** Control characters other than tab and newlines are rejected (log and export injection). */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n\\r]]");

    private CustomFieldValidator() {
    }

    /**
     * @param definitions     the document type's fields
     * @param activeLovValues active value codes of a LOV, by LOV code (empty set if unknown)
     * @param input           submitted custom fields; null means none
     */
    public static CustomFieldsResult validate(List<FieldSpec> definitions,
            Function<String, Set<String>> activeLovValues, Map<String, ?> input) {
        Map<String, ?> values = input == null ? Map.of() : input;
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> normalised = new LinkedHashMap<>();

        Set<String> known = new HashSet<>();
        definitions.forEach(definition -> known.add(definition.key()));
        new TreeSet<>(values.keySet()).stream().filter(key -> !known.contains(key)).forEach(key ->
                violations.add(new Violation(key, FIELD_UNKNOWN, "This field is not defined for the document type.")));

        definitions.stream().sorted(FieldSpec.DISPLAY_ORDER).forEach(definition -> {
            Object raw = values.get(definition.key());
            if (isAbsent(raw) && definition.defaultValue() != null) {
                raw = definition.defaultValue();
            }
            if (isAbsent(raw)) {
                if (definition.mandatory()) {
                    violations.add(new Violation(definition.key(), FIELD_REQUIRED, "This field is required."));
                }
                return;
            }
            Object value = normalise(definition, raw, activeLovValues, violations);
            if (value != null) {
                normalised.put(definition.key(), value);
            }
        });

        return violations.isEmpty() ? new CustomFieldsResult(normalised, List.of())
                : new CustomFieldsResult(Map.of(), violations);
    }

    /** Validates one value for a definition (used for default values); returns the violations. */
    static List<Violation> checkValue(FieldSpec definition, Object raw, Function<String, Set<String>> activeLovValues) {
        List<Violation> violations = new ArrayList<>();
        normalise(definition, raw, activeLovValues, violations);
        return violations;
    }

    private static Object normalise(FieldSpec definition, Object raw, Function<String, Set<String>> activeLovValues,
            List<Violation> violations) {
        String key = definition.key();
        return switch (definition.dataType()) {
            case TEXT -> text(key, raw, definition.maxLength(), violations);
            case NUMBER -> number(key, raw, violations);
            case DATE -> date(key, raw, violations);
            case BOOLEAN -> bool(key, raw, violations);
            case LOV -> lov(key, raw, activeLovValues.apply(definition.lovCode()), violations);
        };
    }

    private static String text(String key, Object raw, Integer maxLength, List<Violation> violations) {
        if (!(raw instanceof String text)) {
            violations.add(new Violation(key, FIELD_TYPE, "Must be text."));
            return null;
        }
        if (CONTROL_CHARACTERS.matcher(text).find()) {
            violations.add(new Violation(key, FIELD_INVALID_CHARACTERS, "Contains control characters."));
            return null;
        }
        if (maxLength != null && text.codePointCount(0, text.length()) > maxLength) {
            violations.add(new Violation(key, FIELD_TOO_LONG, "Must be at most " + maxLength + " characters."));
            return null;
        }
        return text;
    }

    private static BigDecimal number(String key, Object raw, List<Violation> violations) {
        BigDecimal number = null;
        try {
            if (raw instanceof BigDecimal decimal) {
                number = decimal;
            } else if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte
                    || raw instanceof java.math.BigInteger) {
                number = new BigDecimal(raw.toString());
            } else if (raw instanceof Double || raw instanceof Float) {
                double value = ((Number) raw).doubleValue();
                number = Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
            } else if (raw instanceof String text) {
                number = new BigDecimal(text.trim());
            }
        } catch (NumberFormatException e) {
            number = null;
        }
        if (number == null) {
            violations.add(new Violation(key, FIELD_TYPE, "Must be a number."));
            return null;
        }
        if (number.precision() > MAX_PRECISION) {
            violations.add(new Violation(key, FIELD_TYPE, "Must have at most " + MAX_PRECISION + " digits."));
            return null;
        }
        return number;
    }

    private static String date(String key, Object raw, List<Violation> violations) {
        if (raw instanceof LocalDate date) {
            return date.toString();
        }
        if (raw instanceof String text) {
            try {
                return LocalDate.parse(text.trim()).toString();
            } catch (DateTimeParseException e) {
                // fall through
            }
        }
        violations.add(new Violation(key, FIELD_TYPE, "Must be a date (yyyy-MM-dd)."));
        return null;
    }

    private static Boolean bool(String key, Object raw, List<Violation> violations) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.valueOf(text.toLowerCase());
        }
        violations.add(new Violation(key, FIELD_TYPE, "Must be true or false."));
        return null;
    }

    private static String lov(String key, Object raw, Set<String> activeValues, List<Violation> violations) {
        if (raw instanceof String code && activeValues.contains(code)) {
            return code;
        }
        violations.add(new Violation(key, FIELD_LOV_VALUE, "Must be one of the list's active values."));
        return null;
    }

    private static boolean isAbsent(Object raw) {
        return raw == null || (raw instanceof String text && text.isBlank());
    }
}
