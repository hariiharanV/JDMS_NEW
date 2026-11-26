package com.jmr.jdms.metadata.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LovValueRepository extends JpaRepository<LovValue, UUID> {

    List<LovValue> findByLovId(UUID lovId);

    List<LovValue> findByLovIdIn(Collection<UUID> lovIds);
}
