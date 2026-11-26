package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.workflow.api.PendingChangeStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingChangeRepository extends JpaRepository<PendingChange, UUID> {

    /** Loads a change for a decision, locking its row so concurrent decisions on it run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PendingChange c where c.id = :id")
    Optional<PendingChange> findForDecision(@Param("id") UUID id);

    boolean existsBySubjectTypeAndSubjectIdAndStatus(String subjectType, UUID subjectId, PendingChangeStatus status);

    boolean existsBySubjectTypeAndSubjectKeyAndStatus(String subjectType, String subjectKey,
            PendingChangeStatus status);

    List<PendingChange> findByStatus(PendingChangeStatus status);
}
