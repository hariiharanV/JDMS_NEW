package com.jmr.jdms.audit.domain;

import com.jmr.jdms.audit.api.AuditEntry;
import com.jmr.jdms.audit.api.AuditEventView;
import com.jmr.jdms.audit.api.AuditOutcome;
import com.jmr.jdms.audit.api.AuditQueries;
import com.jmr.jdms.audit.api.AuditTrail;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit queries for the current tenant (API outline "Audit"). Filters become JPA criteria with bound parameters;
 * results are newest first by chain position. Each query is recorded as {@code AUDIT_QUERIED} with its filters,
 * in the same transaction, so reading the trail always leaves a trace (spec §4.11).
 */
@Service
public class AuditQueryService implements AuditQueries {

    public static final String AUDIT_QUERIED = "AUDIT_QUERIED";

    private static final JsonMapper JSON = JsonMapper.shared();

    private final AuditEventRepository events;
    private final AuditTrail auditTrail;

    AuditQueryService(AuditEventRepository events, AuditTrail auditTrail) {
        this.events = events;
        this.auditTrail = auditTrail;
    }

    /** Filters of the tenant-wide query; each is optional, {@code from} inclusive and {@code to} exclusive. */
    public record Filter(String resourceType, String resourceId, String actorId, String action,
            AuditOutcome outcome, Instant from, Instant to) {
    }

    @Transactional
    public Page<AuditEventView> search(Filter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new ValidationFailedException("The audit query is invalid.",
                    List.of(new Violation("from", "RANGE_INVALID", "from must be before to.")));
        }
        Page<AuditEventView> result = events.findAll(matching(filter),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "chainSeq"))).map(AuditQueryService::view);
        Map<String, Object> details = filters(filter);
        details.put("page", page);
        details.put("size", size);
        details.put("returned", result.getNumberOfElements());
        auditTrail.record(AuditEntry.success(AUDIT_QUERIED, "AUDIT", "tenant", details));
        return result;
    }

    @Override
    @Transactional
    public List<AuditEventView> forResource(String resourceType, String resourceId, int limit) {
        List<AuditEventView> result = events.findAll(matching(new Filter(resourceType, resourceId, null, null, null,
                null, null)), PageRequest.of(0, limit, Sort.by("chainSeq"))).map(AuditQueryService::view).getContent();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resourceType", resourceType);
        details.put("resourceId", resourceId);
        details.put("returned", result.size());
        auditTrail.record(AuditEntry.success(AUDIT_QUERIED, resourceType, resourceId, details));
        return result;
    }

    private static Specification<AuditEvent> matching(Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.resourceType() != null) {
                predicates.add(cb.equal(root.get("resourceType"), filter.resourceType()));
            }
            if (filter.resourceId() != null) {
                predicates.add(cb.equal(root.get("resourceId"), filter.resourceId()));
            }
            if (filter.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), filter.actorId()));
            }
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.outcome() != null) {
                predicates.add(cb.equal(root.get("outcome"), filter.outcome()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), filter.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Map<String, Object> filters(Filter filter) {
        Map<String, Object> details = new LinkedHashMap<>();
        putIfSet(details, "resourceType", filter.resourceType());
        putIfSet(details, "resourceId", filter.resourceId());
        putIfSet(details, "actorId", filter.actorId());
        putIfSet(details, "action", filter.action());
        putIfSet(details, "outcome", filter.outcome());
        putIfSet(details, "from", filter.from());
        putIfSet(details, "to", filter.to());
        return details;
    }

    private static void putIfSet(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static AuditEventView view(AuditEvent event) {
        return new AuditEventView(event.getId(), event.getChainSeq(), event.getOccurredAt(), event.getActorType(),
                event.getActorId(), event.getAction(), event.getResourceType(), event.getResourceId(),
                event.getOutcome(), event.getClientIp(), event.getRequestId(),
                JSON.readValue(event.getDetails(), Map.class), event.getPrevHash(), event.getHash());
    }
}
