package com.jmr.jdms.workflow.domain;

import com.jmr.jdms.workflow.api.ApprovalDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, UUID> {

    List<ApprovalRecord> findByDocumentIdOrderByDecidedAtAscIdAsc(UUID documentId);

    List<ApprovalRecord> findByProcessInstanceIdOrderByDecidedAtAscIdAsc(String processInstanceId);

    long countByProcessInstanceIdAndDecision(String processInstanceId, ApprovalDecision decision);

    boolean existsByProcessInstanceIdAndRoundAndActorIdAndTaskIdIsNotNull(String processInstanceId, int round,
            UUID actorId);
}
