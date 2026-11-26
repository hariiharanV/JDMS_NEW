package com.jmr.jdms.migration.client;

/** The migration client's bearer token for the core. */
@FunctionalInterface
public interface TokenProvider {

    String token();
}
