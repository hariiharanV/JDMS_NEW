package com.jmr.jdms.metadata.web;

import com.jmr.jdms.metadata.domain.DocumentTypeAdministration;
import com.jmr.jdms.metadata.domain.DocumentTypeAdministration.DocumentTypePayload;
import com.jmr.jdms.metadata.domain.DocumentTypeAdministration.DocumentTypeView;
import com.jmr.jdms.metadata.domain.FieldListAdministration;
import com.jmr.jdms.metadata.domain.FieldSpec;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.PendingChangeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
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

/** {@code /admin/document-types} and {@code /admin/document-types/{id}/fields}. Writes are proposals (202). */
@RestController
@RequestMapping("/api/v1/admin/document-types")
@PreAuthorize("@access.can('ADMIN_METADATA')")
class DocumentTypeAdminController {

    private final DocumentTypeAdministration documentTypes;
    private final FieldListAdministration fieldLists;

    DocumentTypeAdminController(DocumentTypeAdministration documentTypes, FieldListAdministration fieldLists) {
        this.documentTypes = documentTypes;
        this.fieldLists = fieldLists;
    }

    @GetMapping
    PageResponse<DocumentTypeView> list(@RequestParam(required = false) UUID cabinetId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(documentTypes.list(cabinetId, PageRequest.of(page, size, Sort.by("code"))),
                view -> view);
    }

    @GetMapping("/{id}")
    ResponseEntity<DocumentTypeView> get(@PathVariable UUID id) {
        DocumentTypeView view = documentTypes.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    /** Creates the type with its initial fields in one proposal. */
    @PostMapping
    ResponseEntity<PendingChangeView> create(@RequestBody DocumentTypePayload request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(documentTypes.proposeCreate(request));
    }

    @PutMapping("/{id}")
    ResponseEntity<PendingChangeView> update(@PathVariable UUID id, @RequestBody DocumentTypePayload request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(documentTypes.proposeUpdate(id, ETags.requiredVersion(ifMatch), request));
    }

    /** Deactivates: document types are never deleted once documents can refer to them. */
    @DeleteMapping("/{id}")
    ResponseEntity<PendingChangeView> deactivate(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(documentTypes.proposeDeactivate(id, ETags.requiredVersion(ifMatch)));
    }

    @GetMapping("/{id}/fields")
    ResponseEntity<List<FieldSpec>> fields(@PathVariable UUID id) {
        DocumentTypeView type = documentTypes.get(id);
        return ResponseEntity.ok().eTag(ETags.of(type.version())).body(fieldLists.list(id));
    }

    /** Replaces the ordered field list; {@code If-Match} carries the document type's ETag. */
    @PutMapping("/{id}/fields")
    ResponseEntity<PendingChangeView> replaceFields(@PathVariable UUID id, @RequestBody List<FieldSpec> request,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return Accepted.of(fieldLists.proposeReplace(id, ETags.requiredVersion(ifMatch), request));
    }
}
