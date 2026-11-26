package com.jmr.jdms.platform.ids;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562), time-ordered for index locality: 48-bit Unix epoch milliseconds, version 7, 12 random
 * bits, variant 2, 62 random bits. Used for row IDs and blob keys by core and worker.
 */
public final class TimeOrderedIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeOrderedIds() {
    }

    public static UUID next() {
        return next(Clock.systemUTC());
    }

    public static UUID next(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
        long msb = (millis << 16) | 0x7000L | randA;
        long lsb = 0x8000_0000_0000_0000L | randB;
        return new UUID(msb, lsb);
    }
}
