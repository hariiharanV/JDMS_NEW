package com.jmr.jdms.perf.load;

import java.time.Duration;

/**
 * Settings of the load simulations, from system properties ({@code -Dname=value} on the Maven command line) with
 * defaults for the local stack. The dev users' password comes from {@code JDMS_DEV_USER_PASSWORD} (deploy/.env).
 */
final class LoadSettings {

    static final String CORE = property("jdms.core", "http://localhost:8080");
    static final String KEYCLOAK = property("jdms.keycloak", "http://localhost:8180") + "/realms/"
            + property("jdms.realm", "jdms-local");
    static final String WEB_CLIENT = property("jdms.client", "jdms-web");
    static final String REDIRECT_URI = property("jdms.redirect", "http://localhost:5180/callback");

    private LoadSettings() {
    }

    static String property(String name, String fallback) {
        return System.getProperty(name, fallback);
    }

    static int intProperty(String name, int fallback) {
        return Integer.parseInt(property(name, Integer.toString(fallback)));
    }

    static double doubleProperty(String name, double fallback) {
        return Double.parseDouble(property(name, Double.toString(fallback)));
    }

    static Duration seconds(String name, int fallback) {
        return Duration.ofSeconds(intProperty(name, fallback));
    }

    static String password() {
        String password = System.getenv("JDMS_DEV_USER_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set JDMS_DEV_USER_PASSWORD (set -a; . deploy/.env; set +a)");
        }
        return password;
    }
}
