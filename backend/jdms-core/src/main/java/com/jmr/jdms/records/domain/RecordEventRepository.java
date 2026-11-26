package com.jmr.jdms.records.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordEventRepository extends JpaRepository<RecordEvent, UUID> {

    /** The first such event for the account: it starts the clock. */
    Optional<RecordEvent> findFirstByEventTypeAndAccountNoOrderByOccurredAtAsc(String eventType, String accountNo);
}
