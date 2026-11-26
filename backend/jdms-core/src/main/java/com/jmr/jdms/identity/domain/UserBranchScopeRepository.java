package com.jmr.jdms.identity.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBranchScopeRepository extends JpaRepository<UserBranchScope, UserBranchScope.Key> {

    List<UserBranchScope> findByUserId(UUID userId);
}
