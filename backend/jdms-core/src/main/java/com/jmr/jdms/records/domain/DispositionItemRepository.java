package com.jmr.jdms.records.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DispositionItemRepository extends JpaRepository<DispositionItem, UUID> {

    List<DispositionItem> findByBatchIdOrderByDueDateAscIdAsc(UUID batchId);

    /** Documents already waiting in a pending batch. */
    @Query("select i.documentId from DispositionItem i, DispositionBatch b where b.id = i.batchId "
            + "and b.status = com.jmr.jdms.records.domain.BatchStatus.PENDING")
    Set<UUID> findPendingDocumentIds();
}
