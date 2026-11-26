package com.jmr.jdms.tenant.web;

import com.jmr.jdms.tenant.api.BranchDirectory;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's branches, codes and names, for pickers and display (B-17; closes the UI's branch-name gap G2 where it
 * is used). Open to every authenticated caller of the tenant, like the metadata catalog: it is configuration, and
 * documents are authorised one by one.
 */
@RestController
@PreAuthorize("isAuthenticated()")
class BranchController {

    record BranchEntry(String code, String name) {
    }

    private final BranchDirectory branches;

    BranchController(BranchDirectory branches) {
        this.branches = branches;
    }

    @GetMapping("/api/v1/branches")
    List<BranchEntry> branches() {
        return branches.findAll().stream().map(branch -> new BranchEntry(branch.code(), branch.name())).toList();
    }
}
