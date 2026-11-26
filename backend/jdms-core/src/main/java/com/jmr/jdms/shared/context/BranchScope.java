package com.jmr.jdms.shared.context;

import java.util.Set;
import java.util.UUID;

/** The branches whose documents a user may see: all branches, or an explicit set of branch IDs. */
public record BranchScope(boolean all, Set<UUID> branchIds) {

    public static final BranchScope NONE = new BranchScope(false, Set.of());
    public static final BranchScope ALL = new BranchScope(true, Set.of());

    public BranchScope {
        branchIds = Set.copyOf(branchIds);
        if (all && !branchIds.isEmpty()) {
            throw new IllegalArgumentException("ALL scope carries no branch list");
        }
    }

    public static BranchScope of(Set<UUID> branchIds) {
        return new BranchScope(false, branchIds);
    }

    public boolean covers(UUID branchId) {
        return all || branchIds.contains(branchId);
    }
}
