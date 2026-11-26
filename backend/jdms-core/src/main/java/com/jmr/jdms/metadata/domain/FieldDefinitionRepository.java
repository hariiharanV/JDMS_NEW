package com.jmr.jdms.metadata.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, UUID> {

    List<FieldDefinition> findByDocumentTypeId(UUID documentTypeId);

    boolean existsByLovCode(String lovCode);

    List<FieldDefinition> findByLovCode(String lovCode);
}
