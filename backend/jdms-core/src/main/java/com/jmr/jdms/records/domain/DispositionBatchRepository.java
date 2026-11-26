package com.jmr.jdms.records.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispositionBatchRepository extends JpaRepository<DispositionBatch, UUID> {

    List<DispositionBatch> findAllByOrderByPreparedAtDesc();

    /** Locks the batch so it is approved and executed once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DispositionBatch b where b.id = :id")
    Optional<DispositionBatch> findForDecision(@Param("id") UUID id);
}
