package com.jmr.jdms.metadata.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CabinetRepository extends JpaRepository<Cabinet, UUID> {

    Optional<Cabinet> findByCode(String code);

    boolean existsByCode(String code);
}
