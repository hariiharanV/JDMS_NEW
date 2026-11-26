package com.jmr.jdms.metadata.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LovRepository extends JpaRepository<Lov, UUID> {

    Optional<Lov> findByCode(String code);

    boolean existsByCode(String code);

    List<Lov> findByCodeIn(Collection<String> codes);
}
