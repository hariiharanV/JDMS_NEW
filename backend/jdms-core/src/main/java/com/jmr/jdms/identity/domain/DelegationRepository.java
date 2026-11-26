package com.jmr.jdms.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DelegationRepository extends JpaRepository<Delegation, UUID> {

    List<Delegation> findByToUserIdAndApprovedByIsNotNullAndValidFromLessThanEqualAndValidToGreaterThan(UUID toUserId,
            Instant now, Instant alsoNow);

    List<Delegation> findByFromUserIdOrToUserId(UUID fromUserId, UUID toUserId);
}
