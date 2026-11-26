package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.LovAdministration;
import com.jmr.jdms.metadata.domain.LovAdministration.LovPayload;
import com.jmr.jdms.metadata.domain.LovAdministration.LovView;
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

/** {@code /admin/lovs}: lists of values with their values. Writes are proposals (202). */
@RestController
@RequestMapping("/api/v1/admin/lovs")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class LovAdminController {

    private final LovAdministration lovs;

    LovAdminController(LovAdministration lovs) {
        this.lovs = lovs;
    }

    @GetMapping
    PageResponse<LovView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(lovs.list(PageRequest.of(page, size, Sort.by("code"))), view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<LovView> get(@PathVariable UUID id) {
        LovView view = lovs.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    ResponseEntity<PendingChangeView> create(@RequestBody LovPayload request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(lovs.proposeCreate(request));
    }

    /** Replaces name and values; values left out are deactivated, not removed. */
    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody LovPayload request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(lovs.proposeUpdate(id, ETags.requiredVersion(ifMatch), request));
    }

    /** Deletes a list that no field uses. */
    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> delete(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(lovs.proposeDelete(id, ETags.requiredVersion(ifMatch)));
    }
}
