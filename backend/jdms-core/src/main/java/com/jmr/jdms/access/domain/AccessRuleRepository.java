package com.jmr.jdms.access.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessRuleRepository extends JpaRepository<AccessRule, UUID> {
}
