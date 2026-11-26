package com.jmr.jdms.metadata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.shared.error.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FieldDefinitionRulesTest {

    private static final Map<String, Set<String>> LOVS = Map.of("CURRENCY", Set.of("OMR", "USD"), "EMPTY", Set.of());

    @Test
    void validFieldsPass() {
        assertThat(check(List.of(
                spec("amount", FieldDataType.NUMBER, null, null, null, true, false, false),
                spec("currency", FieldDataType.LOV, null, "OMR", "CURRENCY", true, true, false),
                spec("note", FieldDataType.TEXT, 500, null, null, false, false, false),
                spec("salary", FieldDataType.NUMBER, null, null, null, false, false, true)))).isEmpty();
    }

    @Test
    void keysMustBeWellFormedUniqueAndNotStandardFields() {
        assertThat(codes(List.of(
                spec("Amount", FieldDataType.NUMBER, null, null, null, false, false, false),
                spec("cif", FieldDataType.TEXT, 20, null, null, false, false, false),
                spec("note", FieldDataType.TEXT, 20, null, null, false, false, false),
                spec("note", FieldDataType.TEXT, 20, null, null, false, false, false))))
                .containsExactlyInAnyOrder("FIELD_KEY_INVALID", "FIELD_KEY_RESERVED", "FIELD_KEY_DUPLICATE");
    }

    @Test
    void textNeedsALengthAndOnlyTextHasOne() {
        assertThat(codes(List.of(
                spec("a", FieldDataType.TEXT, 5000, null, null, false, false, false),
                spec("b", FieldDataType.NUMBER, 10, null, null, false, false, false))))
                .containsExactlyInAnyOrder("FIELD_MAX_LENGTH_INVALID", "FIELD_MAX_LENGTH_NOT_ALLOWED");
        assertThat(FieldDefinitionRules.withDefaults(spec("c", FieldDataType.TEXT, null, null, null, false, false,
                false)).maxLength()).isEqualTo(FieldDefinitionRules.DEFAULT_TEXT_LENGTH);
    }

    @Test
    void lovFieldsNeedAnExistingNonEmptyListAndOthersNone() {
        assertThat(codes(List.of(
                spec("a", FieldDataType.LOV, null, null, null, false, false, false),
                spec("b", FieldDataType.LOV, null, null, "NOPE", false, false, false),
                spec("c", FieldDataType.LOV, null, null, "EMPTY", false, false, false),
                spec("d", FieldDataType.TEXT, 10, null, "CURRENCY", false, false, false))))
                .containsExactlyInAnyOrder("FIELD_LOV_UNKNOWN", "FIELD_LOV_UNKNOWN", "FIELD_LOV_EMPTY",
                        "FIELD_LOV_NOT_ALLOWED");
    }

    @Test
    void defaultValuesMustBeValidForTheField() {
        assertThat(codes(List.of(
                spec("a", FieldDataType.NUMBER, null, "ten", null, false, false, false),
                spec("b", FieldDataType.LOV, null, "GBP", "CURRENCY", false, false, false),
                spec("c", FieldDataType.DATE, null, "2026-02-30", null, false, false, false),
                spec("d", FieldDataType.TEXT, 3, "toolong", null, false, false, false))))
                .containsOnly("FIELD_DEFAULT_INVALID").hasSize(4);
    }

    @Test
    void facetsMustBeSearchableNotSensitiveAndNotRanges() {
        assertThat(codes(List.of(
                spec("a", FieldDataType.LOV, null, null, "CURRENCY", false, true, false),
                spec("b", FieldDataType.LOV, null, null, "CURRENCY", true, true, true),
                spec("c", FieldDataType.NUMBER, null, null, null, true, true, false))))
                .containsExactlyInAnyOrder("FIELD_FACET_NOT_SEARCHABLE", "FIELD_FACET_SENSITIVE", "FIELD_FACET_TYPE");
    }

    @Test
    void anEnglishLabelIsRequiredAndAnArabicOneIsOptional() {
        FieldSpec noEnglish = new FieldSpec("a", " ", "أ", FieldDataType.BOOLEAN, false, null, null, null, false,
                false, false, 1, 1);
        FieldSpec longArabic = new FieldSpec("b", "B", "ب".repeat(201), FieldDataType.BOOLEAN, false, null, null, null,
                false, false, false, 2, 2);
        assertThat(codes(List.of(noEnglish))).containsExactly("FIELD_LABEL_INVALID");
        assertThat(codes(List.of(longArabic))).containsExactly("FIELD_LABEL_INVALID");

        FieldSpec blankArabic = new FieldSpec("c", "C", " ", FieldDataType.BOOLEAN, false, null, null, null, false,
                false, false, 3, 3);
        assertThat(codes(List.of(blankArabic))).isEmpty();
        // ADR-0023: a blank Arabic label is left out, so screens show the English one
        assertThat(FieldDefinitionRules.withDefaults(blankArabic).labelAr()).isNull();
    }

    @Test
    void numberOfFieldsIsCapped() {
        List<FieldSpec> many = new ArrayList<>();
        IntStream.range(0, FieldDefinitionRules.MAX_FIELDS + 1).forEach(i ->
                many.add(spec("f" + i, FieldDataType.BOOLEAN, null, null, null, false, false, false)));
        assertThat(codes(many)).contains("TOO_MANY_FIELDS");
    }

    private static List<Violation> check(List<FieldSpec> fields) {
        return FieldDefinitionRules.check(fields, LOVS::get);
    }

    private static List<String> codes(List<FieldSpec> fields) {
        return check(fields).stream().map(Violation::code).toList();
    }

    private static FieldSpec spec(String key, FieldDataType type, Integer maxLength, String defaultValue,
            String lovCode, boolean searchable, boolean facet, boolean sensitive) {
        return new FieldSpec(key, "Label " + key, "تسمية " + key, type, false, maxLength, defaultValue, lovCode,
                searchable, facet, sensitive, 1, 1);
    }
}
