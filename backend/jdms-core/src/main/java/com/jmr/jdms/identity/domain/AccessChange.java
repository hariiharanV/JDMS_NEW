package com.jmr.jdms.identity.domain;

import com.jmr.jdms.shared.Classification;
import java.util.List;

/** A requested change of a user's branch scope and clearance. */
public record AccessChange(boolean allBranches, List<String> branchCodes, Classification clearance) {

    public AccessChange {
        branchCodes = branchCodes == null ? List.of() : List.copyOf(branchCodes);
    }
}
