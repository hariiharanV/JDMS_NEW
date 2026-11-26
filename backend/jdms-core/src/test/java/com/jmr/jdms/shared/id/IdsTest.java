package com.jmr.jdms.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Rfc9562Ids() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void timestampIsTheUnixMillisecondsInTheTop48Bits() {
        Instant at = Instant.parse("2026-10-24T12:00:00.123Z");

        UUID id = Ids.newId(Clock.fixed(at, ZoneOffset.UTC));

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() {
        UUID earlier = Ids.newId(Clock.fixed(Instant.parse("2026-10-24T12:00:00.001Z"), ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(Instant.parse("2026-10-24T12:00:00.002Z"), ZoneOffset.UTC));

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void idsAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(100_000);
    }
}
