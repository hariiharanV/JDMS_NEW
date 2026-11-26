package com.jmr.jdms.records.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TombstoneRepository extends JpaRepository<Tombstone, UUID> {

    Optional<Tombstone> findByDocumentId(UUID documentId);
}
