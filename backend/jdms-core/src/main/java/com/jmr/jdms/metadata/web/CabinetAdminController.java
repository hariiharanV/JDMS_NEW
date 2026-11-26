package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.CabinetAdministration;
import com.jmr.jdms.metadata.domain.CabinetAdministration.CabinetPayload;
import com.jmr.jdms.metadata.domain.CabinetAdministration.CabinetView;
import com.jmr.jdms.metadata.domain.FolderAdministration;
import com.jmr.jdms.metadata.domain.FolderAdministration.FolderView;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.PendingChangeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/cabinets} and their folders. Writes are proposals answered with 202; a second tenant admin
 * approves them under {@code /admin/pending-changes}.
 */
@RestController
@RequestMapping("/api/v1/admin/cabinets")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class CabinetAdminController {

    private final CabinetAdministration cabinets;
    private final FolderAdministration folders;

    CabinetAdminController(CabinetAdministration cabinets, FolderAdministration folders) {
        this.cabinets = cabinets;
        this.folders = folders;
    }

    @GetMapping
    PageResponse<CabinetView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(cabinets.list(PageRequest.of(page, size, Sort.by("code"))), view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<CabinetView> get(@PathVariable UUID id) {
        CabinetView view = cabinets.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    ResponseEntity<PendingChangeView> create(@RequestBody CabinetPayload request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(cabinets.proposeCreate(request));
    }

    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody CabinetPayload request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(cabinets.proposeUpdate(id, ETags.requiredVersion(ifMatch), request));
    }

    /** Deactivates: cabinets are never deleted once documents can refer to them. */
    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> deactivate(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(cabinets.proposeDeactivate(id, ETags.requiredVersion(ifMatch)));
    }

    /** One level of the folder tree: root folders, or the children of {@code parentId}. */
    @GetMapping("/{cabinetId}/folders")
    PageResponse<FolderView> folders(@PathVariable UUID cabinetId, @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(folders.children(cabinetId, parentId, PageRequest.of(page, size, Sort.by("name"))),
                view -> view);
    }

    @PostMapping("/{cabinetId}/folders")
    ResponseEntity<PendingChangeView> createFolder(@PathVariable UUID cabinetId,
            @RequestBody FolderAdminController.FolderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(folders.proposeCreate(cabinetId, request.parentId(), request.name()));
    }
}
