package com.jmr.jdms.document.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped. */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    List<DocumentVersion> findByDocumentIdOrderByMajorDescMinorDesc(UUID documentId);

    Optional<DocumentVersion> findByIdAndDocumentId(UUID id, UUID documentId);

    /**
     * Locks the version row for the rest of the transaction. Every background or concurrent writer of a version
     * (processing results, approval outcomes, indexing) loads it this way, so their updates apply one after the
     * other instead of failing each other's optimistic check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DocumentVersion v where v.id = :id and v.documentId = :documentId")
    Optional<DocumentVersion> lockForUpdate(@Param("id") UUID id, @Param("documentId") UUID documentId);

    boolean existsByBlobKey(String blobKey);
}
