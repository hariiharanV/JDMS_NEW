package com.jmr.jdms.workflow.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitteeRepository extends JpaRepository<Committee, UUID> {

    Optional<Committee> findByCode(String code);

    boolean existsByCode(String code);
}
