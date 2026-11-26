package com.jmr.jdms.records.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalHoldItemRepository extends JpaRepository<LegalHoldItem, UUID> {

    List<LegalHoldItem> findByHoldId(UUID holdId);

    Optional<LegalHoldItem> findByHoldIdAndDocumentId(UUID holdId, UUID documentId);

    /** Of these documents, those in any active hold. */
    @Query("select i.documentId from LegalHoldItem i, LegalHold h where h.id = i.holdId "
            + "and h.status = com.jmr.jdms.records.domain.LegalHoldStatus.ACTIVE and i.documentId in :documents")
    Set<UUID> findHeld(@Param("documents") Collection<UUID> documents);
}
