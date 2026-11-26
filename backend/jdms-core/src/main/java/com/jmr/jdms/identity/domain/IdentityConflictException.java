package com.jmr.jdms.identity.domain;

/**
 * The identity provider presents a known username with a new subject (the user was deleted and recreated). The old
 * profile is never reassigned automatically: a recycled username must not inherit someone else's access.
 */
public class IdentityConflictException extends RuntimeException {

    public IdentityConflictException(String message) {
        super(message);
    }
}
