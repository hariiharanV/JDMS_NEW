package com.jmr.jdms.access.api;

import java.util.UUID;

/**
 * The outcome of an authorization decision with what decided it: the rule or grant ID when one decided, for audit
 * and tests (spec §6).
 */
public record Decision(boolean allowed, DecisionReason reason, UUID decidedBy) {

    public static Decision allow(DecisionReason reason, UUID decidedBy) {
        return new Decision(true, reason, decidedBy);
    }

    public static Decision deny(DecisionReason reason, UUID decidedBy) {
        return new Decision(false, reason, decidedBy);
    }
}
