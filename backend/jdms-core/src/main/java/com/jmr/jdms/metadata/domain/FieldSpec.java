package com.jmr.jdms.metadata.domain;

import java.util.Comparator;

/** The definition of one custom field, as proposed by admins and as used by the validation engine. */
public record FieldSpec(
        String key,
        String labelEn,
        String labelAr,
        FieldDataType dataType,
        boolean mandatory,
        Integer maxLength,
        String defaultValue,
        String lovCode,
        boolean searchable,
        boolean facet,
        boolean sensitive,
        int displayOrder,
        int displayRow) {

    /** Form order: row, then order within the row, then key. */
    public static final Comparator<FieldSpec> DISPLAY_ORDER = Comparator.comparingInt(FieldSpec::displayRow)
            .thenComparingInt(FieldSpec::displayOrder).thenComparing(FieldSpec::key);
}
