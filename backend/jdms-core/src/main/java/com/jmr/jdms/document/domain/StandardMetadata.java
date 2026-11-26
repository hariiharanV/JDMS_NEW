package com.jmr.jdms.document.domain;

import java.time.LocalDate;

/** The standard banking fields every document has (spec §4.1); all optional unless the type requires them. */
public record StandardMetadata(
        String cif,
        String customerName,
        String accountNo,
        String collateralCode,
        String contractRef,
        String transactionRef,
        String applicationRef,
        LocalDate expiryDate,
        String physicalLocation,
        String physicalRef) {

    public static final StandardMetadata EMPTY = new StandardMetadata(null, null, null, null, null, null, null, null,
            null, null);
}
