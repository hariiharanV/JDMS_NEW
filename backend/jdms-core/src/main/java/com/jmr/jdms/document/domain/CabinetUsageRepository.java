package com.jmr.jdms.document.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CabinetUsageRepository extends JpaRepository<CabinetUsage, UUID> {

    /** The cabinet's usage row, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from CabinetUsage u where u.cabinetId = :cabinetId")
    Optional<CabinetUsage> lock(@Param("cabinetId") UUID cabinetId);
}
