package com.jmr.jdms.shared.id;

import com.jmr.jdms.platform.ids.TimeOrderedIds;
import java.time.Clock;
import java.util.UUID;

/** Generates identifiers for JDMS Next rows: UUID version 7 (RFC 9562), time-ordered for index locality. */
public final class Ids {

    private Ids() {
    }

    public static UUID newId() {
        return TimeOrderedIds.next();
    }

    static UUID newId(Clock clock) {
        return TimeOrderedIds.next(clock);
    }
}
