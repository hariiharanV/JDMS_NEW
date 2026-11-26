package com.jmr.jdms.workflow.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitteeMemberRepository extends JpaRepository<CommitteeMember, UUID> {

    List<CommitteeMember> findByCommitteeIdOrderByCreatedAtAscIdAsc(UUID committeeId);

    void deleteByCommitteeId(UUID committeeId);
}
