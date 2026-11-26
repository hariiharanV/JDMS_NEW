package com.jmr.jdms.support;

import com.jmr.jdms.access.api.AccessProvisioning;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.domain.Branch;
import com.jmr.jdms.tenant.domain.BranchRepository;
import com.jmr.jdms.tenant.domain.Tenant;
import com.jmr.jdms.tenant.domain.TenantRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates tenants with branches and the baseline access rules for tests; codes are unique per call so tests sharing a
 * database never collide.
 */
@Component
public class TestTenants {

    private final TenantRepository tenants;
    private final BranchRepository branches;
    private final TransactionTemplate transactions;
    private final AccessProvisioning accessProvisioning;

    public TestTenants(TenantRepository tenants, BranchRepository branches, TransactionTemplate transactions,
            AccessProvisioning accessProvisioning) {
        this.tenants = tenants;
        this.branches = branches;
        this.transactions = transactions;
        this.accessProvisioning = accessProvisioning;
    }

    public CreatedTenant create(String... branchCodes) {
        String code = "T" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36).toUpperCase();
        return createWithCode(code.substring(0, Math.min(code.length(), 12)), branchCodes);
    }

    public CreatedTenant createWithCode(String code, String... branchCodes) {
        Tenant tenant = transactions.execute(status -> tenants.save(new Tenant(code, "Tenant " + code, "en")));
        Map<String, UUID> branchIds = new LinkedHashMap<>();
        TenantScope.run(tenant.getId(), () -> transactions.executeWithoutResult(status -> {
            for (String branchCode : branchCodes) {
                branchIds.put(branchCode, branches.save(new Branch(branchCode, "Branch " + branchCode, null)).getId());
            }
        }));
        accessProvisioning.provisionBaseline(tenant.getId());
        return new CreatedTenant(tenant.getId(), code, branchIds);
    }

    public void suspend(UUID tenantId) {
        transactions.executeWithoutResult(status -> tenants.findById(tenantId).orElseThrow().suspend());
    }

    public record CreatedTenant(UUID id, String code, Map<String, UUID> branchIds) {

        public UUID branch(String code) {
            return branchIds.get(code);
        }
    }
}
