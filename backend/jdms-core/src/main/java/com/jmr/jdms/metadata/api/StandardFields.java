package com.jmr.jdms.metadata.api;

import java.util.Set;

/**
 * Standard banking fields present on every document as columns (spec §4.1, DM {@code document}). Custom field keys
 * may not reuse these names; document types can make some of them mandatory.
 */
public final class StandardFields {

    public static final String CIF = "cif";
    public static final String CUSTOMER_NAME = "customer_name";
    public static final String ACCOUNT_NO = "account_no";
    public static final String COLLATERAL_CODE = "collateral_code";
    public static final String CONTRACT_REF = "contract_ref";
    public static final String TRANSACTION_REF = "transaction_ref";
    public static final String APPLICATION_REF = "application_ref";
    public static final String BRANCH_CODE = "branch_code";
    public static final String EXPIRY_DATE = "expiry_date";
    public static final String PHYSICAL_LOCATION = "physical_location";
    public static final String PHYSICAL_REF = "physical_ref";

    /** Fields a document type may declare mandatory. */
    public static final Set<String> ALL = Set.of(CIF, CUSTOMER_NAME, ACCOUNT_NO, COLLATERAL_CODE, CONTRACT_REF,
            TRANSACTION_REF, APPLICATION_REF, BRANCH_CODE, EXPIRY_DATE, PHYSICAL_LOCATION, PHYSICAL_REF);

    /** Names reserved for document columns; custom field keys may not use them either. */
    public static final Set<String> RESERVED = union(ALL, Set.of("id", "title", "status", "classification",
            "cabinet", "cabinet_code", "document_type", "document_type_code", "folder_id", "branch_id", "tenant_id",
            "created_at", "created_by", "updated_at", "updated_by", "content", "version"));

    private StandardFields() {
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
