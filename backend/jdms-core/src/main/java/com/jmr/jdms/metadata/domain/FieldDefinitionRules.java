package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.StandardFields;
import com.jmr.jdms.shared.error.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Rules for a document type's list of field definitions (admin input). Pure; LOV values come from the caller. */
public final class FieldDefinitionRules {

    public static final int MAX_FIELDS = 100;
    public static final int MAX_TEXT_LENGTH = 4000;
    public static final int DEFAULT_TEXT_LENGTH = 255;

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,49}");

    private FieldDefinitionRules() {
    }

    /**
     * @param activeLovValues active value codes by LOV code; null when the LOV does not exist
     */
    public static List<Violation> check(List<FieldSpec> fields, Function<String, Set<String>> activeLovValues) {
        List<Violation> violations = new ArrayList<>();
        if (fields.size() > MAX_FIELDS) {
            violations.add(new Violation("fields", "TOO_MANY_FIELDS", "At most " + MAX_FIELDS + " fields."));
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FieldSpec field = fields.get(i);
            String path = "fields[" + i + "]";
            if (field.key() == null || !KEY.matcher(field.key()).matches()) {
                violations.add(new Violation(path + ".key", "FIELD_KEY_INVALID",
                        "Key must be 1-50 of a-z, 0-9 and _, starting with a letter."));
                continue;
            }
            path = "fields." + field.key();
            if (!keys.add(field.key())) {
                violations.add(new Violation(path, "FIELD_KEY_DUPLICATE", "Key is used more than once."));
            }
            if (StandardFields.RESERVED.contains(field.key())) {
                violations.add(new Violation(path, "FIELD_KEY_RESERVED",
                        "Key is a standard document field; use the standard field instead."));
            }
            checkLabels(field, path, violations);
            if (field.dataType() == null) {
                violations.add(new Violation(path + ".dataType", "FIELD_TYPE_REQUIRED", "Data type is required."));
                continue;
            }
            checkTypeSpecific(field, path, activeLovValues, violations);
            if (field.facet() && !field.searchable()) {
                violations.add(new Violation(path + ".facet", "FIELD_FACET_NOT_SEARCHABLE",
                        "A facet field must also be searchable."));
            }
            if (field.facet() && field.sensitive()) {
                violations.add(new Violation(path + ".facet", "FIELD_FACET_SENSITIVE",
                        "Sensitive fields cannot be facets: facet counts would reveal their values."));
            }
            if (field.facet() && (field.dataType() == FieldDataType.NUMBER || field.dataType() == FieldDataType.DATE)) {
                violations.add(new Violation(path + ".facet", "FIELD_FACET_TYPE",
                        "NUMBER and DATE fields are filtered by range, not used as facets."));
            }
            if (field.displayOrder() < 0 || field.displayRow() < 1) {
                violations.add(new Violation(path + ".displayOrder", "FIELD_DISPLAY_INVALID",
                        "Display order must be 0 or more and display row 1 or more."));
            }
        }
        return violations;
    }

    /**
     * TEXT fields without a maximum length get the default; other types never carry one. A blank Arabic label is
     * left out, so screens show the English one.
     */
    public static FieldSpec withDefaults(FieldSpec field) {
        Integer maxLength = field.dataType() == FieldDataType.TEXT && field.maxLength() == null
                ? Integer.valueOf(DEFAULT_TEXT_LENGTH)
                : field.maxLength();
        return new FieldSpec(field.key(), field.labelEn(), MetadataChangeSupport.optional(field.labelAr()),
                field.dataType(), field.mandatory(), maxLength, field.defaultValue(), field.lovCode(),
                field.searchable(), field.facet(), field.sensitive(), field.displayOrder(), field.displayRow());
    }

    private static void checkLabels(FieldSpec field, String path, List<Violation> violations) {
        if (blankOrTooLong(field.labelEn()) || field.labelAr() != null && field.labelAr().length() > 200) {
            violations.add(new Violation(path + ".label", "FIELD_LABEL_INVALID",
                    "An English label is required; English and Arabic labels have at most 200 characters."));
        }
    }

    private static void checkTypeSpecific(FieldSpec field, String path,
            Function<String, Set<String>> activeLovValues, List<Violation> violations) {
        if (field.dataType() == FieldDataType.TEXT) {
            if (field.maxLength() == null || field.maxLength() < 1 || field.maxLength() > MAX_TEXT_LENGTH) {
                violations.add(new Violation(path + ".maxLength", "FIELD_MAX_LENGTH_INVALID",
                        "TEXT fields need a maximum length of 1 to " + MAX_TEXT_LENGTH + "."));
            }
        } else if (field.maxLength() != null) {
            violations.add(new Violation(path + ".maxLength", "FIELD_MAX_LENGTH_NOT_ALLOWED",
                    "Only TEXT fields have a maximum length."));
        }

        if (field.dataType() == FieldDataType.LOV) {
            Set<String> values = field.lovCode() == null ? null : activeLovValues.apply(field.lovCode());
            if (values == null) {
                violations.add(new Violation(path + ".lovCode", "FIELD_LOV_UNKNOWN",
                        "LOV fields need the code of an existing list of values."));
                return;
            }
            if (values.isEmpty()) {
                violations.add(new Violation(path + ".lovCode", "FIELD_LOV_EMPTY", "The list has no active values."));
            }
        } else if (field.lovCode() != null) {
            violations.add(new Violation(path + ".lovCode", "FIELD_LOV_NOT_ALLOWED",
                    "Only LOV fields have a list of values."));
        }

        if (field.defaultValue() != null) {
            Function<String, Set<String>> lookup = code -> {
                Set<String> values = activeLovValues.apply(code);
                return values == null ? Set.of() : values;
            };
            CustomFieldValidator.checkValue(field, field.defaultValue(), lookup).forEach(violation ->
                    violations.add(new Violation(path + ".defaultValue", "FIELD_DEFAULT_INVALID",
                            "Default value is not valid for the field: " + violation.message())));
        }
    }

    private static boolean blankOrTooLong(String label) {
        return label == null || label.isBlank() || label.length() > 200;
    }
}
