package com.jmr.jdms.metadata.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    Optional<DocumentType> findByCode(String code);

    boolean existsByCode(String code);

    Page<DocumentType> findByCabinetId(UUID cabinetId, Pageable pageable);

    List<DocumentType> findByRetentionPolicyId(UUID retentionPolicyId);
}
