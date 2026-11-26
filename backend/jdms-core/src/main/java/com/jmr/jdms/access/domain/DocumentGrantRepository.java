package com.jmr.jdms.access.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentGrantRepository extends JpaRepository<DocumentGrant, UUID> {

    List<DocumentGrant> findByUserIdAndValidUntilAfterAndRevokedAtIsNull(UUID userId, Instant now);
}
