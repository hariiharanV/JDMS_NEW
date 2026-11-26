package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.CatalogService;
import com.jmr.jdms.metadata.domain.CatalogService.CabinetEntry;
import com.jmr.jdms.metadata.domain.CatalogService.FolderEntry;
import com.jmr.jdms.metadata.domain.CatalogService.TypeEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's active metadata model for browsing and upload forms (API outline "Browse and search"). Open to every
 * authenticated caller of the tenant: it is configuration; documents are authorised one by one.
 */
@RestController
@PreAuthorize("isAuthenticated()")
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/cabinets")
    List<CabinetEntry> cabinets() {
        return catalog.cabinets();
    }

    @GetMapping("/api/v1/cabinets/{id}/folders")
    List<FolderEntry> folders(@PathVariable UUID id) {
        return catalog.folders(id);
    }

    @GetMapping("/api/v1/document-types")
    List<TypeEntry> documentTypes(@RequestParam(required = false) UUID cabinetId) {
        return catalog.documentTypes(cabinetId);
    }
}
