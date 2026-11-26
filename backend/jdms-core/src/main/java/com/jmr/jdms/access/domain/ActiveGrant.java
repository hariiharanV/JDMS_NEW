package com.jmr.jdms.access.domain;

import com.jmr.jdms.access.api.Action;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A document grant as the engine sees it. */
public record ActiveGrant(UUID id, UUID documentId, Set<Action> actions, Instant validUntil, Instant revokedAt) {

    boolean validAt(Instant now) {
        return revokedAt == null && now.isBefore(validUntil);
    }
}
