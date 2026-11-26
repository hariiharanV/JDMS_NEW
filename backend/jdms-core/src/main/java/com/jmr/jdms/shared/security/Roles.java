package com.jmr.jdms.shared.security;

/** Realm role codes used in code (Keycloak realm {@code jdms-local}; spec §3 actors). */
public final class Roles {

    public static final String MAKER = "MAKER";
    public static final String CHECKER = "CHECKER";
    public static final String COMMITTEE_MEMBER = "COMMITTEE_MEMBER";
    public static final String VIEWER = "VIEWER";
    public static final String RECORDS_OFFICER = "RECORDS_OFFICER";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String SECURITY_ADMIN = "SECURITY_ADMIN";
    public static final String INTEGRATION_CLIENT = "INTEGRATION_CLIENT";
    /** The legacy migration client (ADR-0020). */
    public static final String MIGRATION = "MIGRATION";

    private Roles() {
    }
}
