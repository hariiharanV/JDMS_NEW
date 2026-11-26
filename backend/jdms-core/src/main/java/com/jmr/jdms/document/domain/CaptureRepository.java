package com.jmr.jdms.document.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaptureRepository extends JpaRepository<Capture, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Capture c where c.id = :id")
    Optional<Capture> lockForUpdate(@Param("id") UUID id);

    long countByCreatedByAndStatus(String createdBy, CaptureStatus status);

    List<Capture> findByExpiresAtBefore(Instant time, Limit limit);

    @Query("select count(c) > 0 from Capture c where c.blobKey = :key or c.suggestionsBlobKey = :key")
    boolean referencesBlob(@Param("key") String blobKey);
}
