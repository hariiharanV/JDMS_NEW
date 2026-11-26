package com.jmr.jdms.document.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped. */
public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

    Optional<Document> findByLegacyDocumentId(String legacyDocumentId);

    boolean existsByFolderId(UUID folderId);

    @Query("select count(distinct d.id) from DocumentVersion v, Document d where v.documentId = d.id "
            + "and d.cabinetId = :cabinetId and d.cif = :cif and v.sha256 = :sha256 and d.status <> 'DELETED' "
            + "and d.status <> 'DISPOSED'")
    long countWithSameContent(@Param("cabinetId") UUID cabinetId, @Param("cif") String cif,
            @Param("sha256") String sha256);

    /** IDs in ID order after {@code after}, for keyset-paged full scans (index rebuild). */
    @Query("select d.id from Document d where d.id > :after order by d.id")
    List<UUID> findIdsAfter(@Param("after") UUID after, Limit limit);

    @Query("select d from Document d where d.documentTypeId in :types and d.id > :after "
            + "and d.status <> com.jmr.jdms.document.domain.DocumentStatus.DISPOSED order by d.id")
    List<Document> findForRetention(@Param("types") Collection<UUID> types, @Param("after") UUID after, Limit limit);
}
