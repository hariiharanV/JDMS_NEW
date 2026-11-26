package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.FolderAdministration;
import com.jmr.jdms.metadata.domain.FolderAdministration.FolderView;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A single folder: read, move or rename, delete (empty folders only). Created under its cabinet. */
@RestController
@RequestMapping("/api/v1/admin/folders")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class FolderAdminController {

    private final FolderAdministration folders;

    FolderAdminController(FolderAdministration folders) {
        this.folders = folders;
    }

    record FolderRequest(UUID parentId, String name) {
    }

    @GetMapping("/{id}")
    ResponseEntity<FolderView> get(@PathVariable UUID id) {
        FolderView view = folders.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    /** Renames and/or moves the folder ({@code parentId} null = cabinet root). */
    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody FolderRequest request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(folders.proposeUpdate(id, ETags.requiredVersion(ifMatch), request.parentId(),
                request.name()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> delete(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(folders.proposeDelete(id, ETags.requiredVersion(ifMatch)));
    }
}
