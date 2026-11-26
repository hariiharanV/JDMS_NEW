package com.jmr.jdms.access.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by modules owning rule scopes (metadata: cabinets, types, folders; document: documents), so access
 * rules can be validated and labelled without the access module depending on them.
 */
public interface ScopeResolver {

    boolean supports(ScopeType type);

    /** A label for an existing scope of the current tenant; empty when it does not exist. */
    Optional<String> describe(ScopeType type, UUID id);
}
