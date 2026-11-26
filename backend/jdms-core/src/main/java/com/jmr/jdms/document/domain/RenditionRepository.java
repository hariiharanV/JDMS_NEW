package com.jmr.jdms.document.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RenditionRepository extends JpaRepository<Rendition, UUID> {

    Optional<Rendition> findByVersionIdAndKind(UUID versionId, String kind);

    List<Rendition> findByVersionId(UUID versionId);

    List<Rendition> findByVersionIdInAndKind(Collection<UUID> versionIds, String kind);

    boolean existsByBlobKey(String blobKey);
}
