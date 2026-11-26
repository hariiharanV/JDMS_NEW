package com.jmr.jdms.audit.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped. Append-only: the application only inserts and reads. */
public interface AuditVerificationRepository extends JpaRepository<AuditVerification, UUID> {

    Optional<AuditVerification> findFirstByOrderByFinishedAtDesc();
}
