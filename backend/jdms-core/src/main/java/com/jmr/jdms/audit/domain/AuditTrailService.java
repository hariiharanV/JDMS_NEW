package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.ActorType;
import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.context.RequestMetadata;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.tenant.TenantScope;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditTrailService implements AuditTrail {

    private final AuditEventRepository events;
    private final AuditChainStore chain;
    private final Clock clock;

    @Autowired
    AuditTrailService(AuditEventRepository events, AuditChainStore chain) {
        this(events, chain, Clock.systemUTC());
    }

    AuditTrailService(AuditEventRepository events, AuditChainStore chain, Clock clock) {
        this.events = events;
        this.chain = chain;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        UUID tenantId = TenantScope.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("Audit events need a tenant in TenantScope"));
        RequestMetadata metadata = RequestMetadata.current().orElse(null);
        AuditRecordFields fields = new AuditRecordFields(
                Ids.newId(),
                tenantId,
                // PostgreSQL keeps microseconds: hash exactly what is stored
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                entry.actorType() != null ? entry.actorType() : currentActorType(),
                entry.actorId() != null ? entry.actorId() : currentActorId(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                metadata == null ? null : metadata.clientIp(),
                metadata == null ? null : metadata.requestId(),
                AuditHasher.canonicalJson(entry.details()));

        AuditChainStore.ChainHead head = chain.lockHead(tenantId);
        String hash = AuditHasher.hash(fields, head.lastHash());
        events.save(new AuditEvent(fields, head.eventCount() + 1, head.lastHash(), hash));
        chain.advance(tenantId, fields.id(), hash);
    }

    private static ActorType currentActorType() {
        return RequestContext.current()
                .map(context -> context.principalType() == PrincipalType.CLIENT ? ActorType.CLIENT : ActorType.USER)
                .orElse(ActorType.SYSTEM);
    }

    private static String currentActorId() {
        return RequestContext.current().map(context -> context.subjectId().toString()).orElse("system");
    }
}
