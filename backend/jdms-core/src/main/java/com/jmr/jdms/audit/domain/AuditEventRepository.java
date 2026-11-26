package com.jmr.jdms.audit.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Tenant-scoped. Append-only: the application only inserts and reads. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByResourceTypeAndResourceIdOrderByOccurredAt(String resourceType, String resourceId);

    List<AuditEvent> findByActionOrderByOccurredAt(String action);

    /** One page of the chain in order, for verification. */
    List<AuditEvent> findByChainSeqBetweenOrderByChainSeq(long fromSeq, long toSeq, Limit limit);
}
