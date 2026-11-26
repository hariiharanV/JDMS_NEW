package com.jmr.jdms.tenant.domain;

import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import com.jmr.jdms.tenant.api.TenantDirectory;
import com.jmr.jdms.tenant.api.TenantInfo;
import com.jmr.jdms.tenant.api.TenantLanguages;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class TenantDirectoryService implements TenantDirectory, BranchDirectory, TenantLanguages {

    private final TenantRepository tenants;
    private final BranchRepository branches;

    TenantDirectoryService(TenantRepository tenants, BranchRepository branches) {
        this.tenants = tenants;
        this.branches = branches;
    }

    @Override
    public Optional<TenantInfo> findById(UUID id) {
        return tenants.findById(id).map(tenant -> new TenantInfo(tenant.getId(), tenant.getCode(), tenant.getName(),
                tenant.getDefaultLocale(), tenant.getLanguages()));
    }

    @Override
    public Optional<TenantInfo> findActiveByCode(String code) {
        return tenants.findByCode(code)
                .filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
                .map(tenant -> new TenantInfo(tenant.getId(), tenant.getCode(), tenant.getName(),
                        tenant.getDefaultLocale(), tenant.getLanguages()));
    }

    @Override
    public List<TenantInfo> findAllActive() {
        return tenants.findAll().stream().filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
                .map(tenant -> new TenantInfo(tenant.getId(), tenant.getCode(), tenant.getName(),
                        tenant.getDefaultLocale(), tenant.getLanguages()))
                .toList();
    }

    @Override
    public LanguageSettings get(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        return new LanguageSettings(tenant.getLanguages(), tenant.getDefaultLocale(), tenant.getVersionLock());
    }

    @Override
    @Transactional
    public boolean change(UUID tenantId, long version, List<String> languages) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        if (tenant.getVersionLock() != version) {
            return false;
        }
        tenant.changeLanguages(languages);
        return true;
    }

    @Override
    public List<BranchInfo> findByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return branches.findByCodeIn(codes).stream().map(TenantDirectoryService::info).toList();
    }

    @Override
    public List<BranchInfo> findAll() {
        return branches.findAll(org.springframework.data.domain.Sort.by("code")).stream()
                .map(TenantDirectoryService::info).toList();
    }

    @Override
    public List<BranchInfo> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return branches.findAllById(ids).stream().map(TenantDirectoryService::info).toList();
    }

    private static BranchInfo info(Branch branch) {
        return new BranchInfo(branch.getId(), branch.getCode(), branch.getName());
    }
}
