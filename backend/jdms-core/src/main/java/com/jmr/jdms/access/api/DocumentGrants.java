package com.jmr.jdms.access.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Time-bound grants of actions on one document to a named user (retrieval requests, shares). */
public interface DocumentGrants {

    UUID grant(UUID documentId, UUID userId, Set<Action> actions, Instant validUntil, GrantSource source,
            UUID sourceId);

    void revoke(UUID grantId);
}
