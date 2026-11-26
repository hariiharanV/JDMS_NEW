package com.jmr.jdms.metadata.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findByCabinetId(UUID cabinetId);

    Page<Folder> findByCabinetIdAndParentId(UUID cabinetId, UUID parentId, Pageable pageable);

    Page<Folder> findByCabinetIdAndParentIdIsNull(UUID cabinetId, Pageable pageable);

    boolean existsByParentId(UUID parentId);

    Optional<Folder> findByCabinetIdAndParentIdAndName(UUID cabinetId, UUID parentId, String name);

    Optional<Folder> findByCabinetIdAndParentIdIsNullAndName(UUID cabinetId, String name);
}
