package com.jmr.jdms.metadata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.metadata.api.CustomFieldsResult;
import com.jmr.jdms.shared.error.Violation;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomFieldValidatorTest {

    private static final List<FieldSpec> LOAN = List.of(
            field("facility_type", FieldDataType.LOV, true, null, null, "FACILITY_TYPE", 1, 1),
            field("amount", FieldDataType.NUMBER, true, null, null, null, 2, 1),
            field("currency", FieldDataType.LOV, true, null, "OMR", "CURRENCY", 3, 1),
            field("origination_date", FieldDataType.DATE, false, null, null, null, 1, 2),
            field("secured", FieldDataType.BOOLEAN, false, null, null, null, 2, 2),
            field("officer", FieldDataType.TEXT, false, 10, null, null, 3, 2));

    private static final Map<String, Set<String>> LOVS = Map.of(
            "FACILITY_TYPE", Set.of("HOME_LOAN", "AUTO_LOAN"),
            "CURRENCY", Set.of("OMR", "USD"));

    @Test
    void validValuesAreNormalisedInDisplayOrderWithDefaults() {
        CustomFieldsResult result = validate(Map.of("facility_type", "HOME_LOAN", "amount", "125000.50",
                "origination_date", "2026-10-24", "secured", "TRUE", "officer", "Sami"));

        assertThat(result.valid()).isTrue();
        assertThat(result.values()).containsExactly(
                Map.entry("facility_type", "HOME_LOAN"),
                Map.entry("amount", new BigDecimal("125000.50")),
                Map.entry("currency", "OMR"), // default
                Map.entry("origination_date", "2026-10-24"),
                Map.entry("secured", true),
                Map.entry("officer", "Sami"));
    }

    @Test
    void jsonNumbersAreAccepted() {
        assertThat(validate(Map.of("facility_type", "AUTO_LOAN", "amount", 5000)).values().get("amount"))
                .isEqualTo(new BigDecimal("5000"));
        assertThat(validate(Map.of("facility_type", "AUTO_LOAN", "amount", 12.5)).values().get("amount"))
                .isEqualTo(new BigDecimal("12.5"));
    }

    @Test
    void mandatoryFieldsAreRequiredAndBlankCountsAsMissing() {
        CustomFieldsResult result = validate(Map.of("facility_type", "  "));

        assertThat(result.valid()).isFalse();
        assertThat(result.values()).isEmpty();
        assertThat(codes(result)).containsExactlyInAnyOrder("facility_type:FIELD_REQUIRED", "amount:FIELD_REQUIRED");
    }

    @Test
    void unknownFieldsAreRejected() {
        assertThat(codes(validate(Map.of("facility_type", "HOME_LOAN", "amount", 1, "colour", "red"))))
                .containsExactly("colour:FIELD_UNKNOWN");
    }

    @Test
    void typeErrorsAreReportedPerField() {
        Map<String, Object> input = new HashMap<>();
        input.put("facility_type", "BOAT_LOAN");
        input.put("amount", "12,000");
        input.put("currency", 42);
        input.put("origination_date", "24/10/2026");
        input.put("secured", "yes");
        input.put("officer", 7);

        assertThat(codes(validate(input))).containsExactlyInAnyOrder(
                "facility_type:FIELD_LOV_VALUE", "amount:FIELD_TYPE", "currency:FIELD_LOV_VALUE",
                "origination_date:FIELD_TYPE", "secured:FIELD_TYPE", "officer:FIELD_TYPE");
    }

    @Test
    void textLengthCountsCharactersNotBytes() {
        assertThat(validate(Map.of("facility_type", "HOME_LOAN", "amount", 1, "officer", "سامي العبري"))
                .valid()).isFalse(); // 11 characters > 10
        assertThat(validate(Map.of("facility_type", "HOME_LOAN", "amount", 1, "officer", "سامي العبر"))
                .valid()).isTrue();
    }

    @Test
    void controlCharactersInTextAreRejected() {
        assertThat(codes(validate(Map.of("facility_type", "HOME_LOAN", "amount", 1, "officer", "a\u0000b"))))
                .containsExactly("officer:FIELD_INVALID_CHARACTERS");
    }

    @Test
    void numbersBeyondPortablePrecisionAreRejected() {
        assertThat(codes(validate(Map.of("facility_type", "HOME_LOAN", "amount", "1".repeat(39)))))
                .containsExactly("amount:FIELD_TYPE");
    }

    @Test
    void inactiveLovValuesAreRejected() {
        CustomFieldsResult result = CustomFieldValidator.validate(LOAN,
                code -> code.equals("CURRENCY") ? Set.of("USD") : LOVS.get(code),
                Map.of("facility_type", "HOME_LOAN", "amount", 1));

        assertThat(codes(result)).containsExactly("currency:FIELD_LOV_VALUE"); // default OMR no longer active
    }

    @Test
    void messagesNeverEchoSubmittedValues() {
        CustomFieldsResult result = validate(Map.of("facility_type", "SECRET-VALUE-123", "amount", "SECRET-456"));

        assertThat(result.violations()).extracting(Violation::message)
                .allSatisfy(message -> assertThat(message).doesNotContain("SECRET"));
    }

    @Test
    void noInputIsLikeEmptyInput() {
        assertThat(codes(CustomFieldValidator.validate(LOAN, LOVS::get, null)))
                .containsExactlyInAnyOrder("facility_type:FIELD_REQUIRED", "amount:FIELD_REQUIRED");
    }

    private static CustomFieldsResult validate(Map<String, ?> input) {
        return CustomFieldValidator.validate(LOAN, code -> LOVS.getOrDefault(code, Set.of()), input);
    }

    private static List<String> codes(CustomFieldsResult result) {
        return result.violations().stream().map(v -> v.field() + ":" + v.code()).toList();
    }

    static FieldSpec field(String key, FieldDataType type, boolean mandatory, Integer maxLength, String defaultValue,
            String lovCode, int order, int row) {
        return new FieldSpec(key, key, key, type, mandatory, maxLength, defaultValue, lovCode, true, false, false,
                order, row);
    }
}
