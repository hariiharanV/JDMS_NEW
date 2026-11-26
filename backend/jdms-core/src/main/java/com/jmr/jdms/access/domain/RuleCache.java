package com.jmr.jdms.access.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Each tenant's approved rules, cached for at most the configured TTL (≤ 60 s, spec §6 revocation) and evicted on
 * change events on this node; other nodes pick changes up within the TTL.
 */
@Component
class RuleCache {

    private record Entry(List<CompiledRule> rules, Instant loadedAt) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock = Clock.systemUTC();

    RuleCache(@Value("${jdms.access.rule-cache-ttl:PT30S}") Duration ttl) {
        if (ttl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalStateException("jdms.access.rule-cache-ttl must be at most 60 seconds (revocation)");
        }
        this.ttl = ttl;
    }

    List<CompiledRule> get(UUID tenantId, Supplier<List<CompiledRule>> loader) {
        Instant now = clock.instant();
        Entry entry = entries.get(tenantId);
        if (entry == null || entry.loadedAt().plus(ttl).isBefore(now)) {
            entry = new Entry(List.copyOf(loader.get()), now);
            entries.put(tenantId, entry);
        }
        return entry.rules();
    }

    void evict(UUID tenantId) {
        entries.remove(tenantId);
    }
}
