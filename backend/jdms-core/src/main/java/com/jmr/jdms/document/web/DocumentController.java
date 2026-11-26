package com.jmr.jdms.document.web;

import com.jmr.jdms.audit.api.AuditEventView;
import com.jmr.jdms.document.domain.DocumentDeletionService;
import com.jmr.jdms.document.domain.DocumentDetailView;
import com.jmr.jdms.document.domain.DocumentFieldsInput;
import com.jmr.jdms.document.domain.DocumentMetadataService;
import com.jmr.jdms.document.domain.DocumentQueryService.DocumentFilter;
import com.jmr.jdms.document.domain.DocumentQueryService;
import com.jmr.jdms.document.domain.DocumentStatus;
import com.jmr.jdms.document.domain.DocumentUploadService;
import com.jmr.jdms.document.domain.DocumentView;
import com.jmr.jdms.document.domain.UploadOutcome;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.web.ETags;
import com.jmr.jdms.shared.web.PageResponse;
import com.jmr.jdms.workflow.api.ApprovalRecordView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code /documents}: upload, read, list and metadata changes (spec §4.2, §4.3). */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

    private final DocumentUploadService uploads;
    private final DocumentQueryService queries;
    private final DocumentMetadataService metadata;
    private final JsonMapper jsonMapper;
    private final DocumentDeletionService deletions;

    DocumentController(DocumentUploadService uploads, DocumentQueryService queries, DocumentMetadataService metadata,
            JsonMapper jsonMapper, DocumentDeletionService deletions) {
        this.uploads = uploads;
        this.queries = queries;
        this.metadata = metadata;
        this.jsonMapper = jsonMapper;
        this.deletions = deletions;
    }

    /**
     * Multipart upload: a {@code metadata} part (JSON) followed by one {@code file} part. Requires
     * {@code Idempotency-Key}; a replay with the same key and request returns the original response.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<JsonNode> upload(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UploadOutcome outcome = StreamingMultipart.read(request, (fields, file) -> {
            String json = fields.get("metadata");
            if (json == null) {
                throw ApiException.unprocessable("METADATA_REQUIRED", "A metadata part must come before the file.");
            }
            if (file == null) {
                throw ApiException.unprocessable("FILE_REQUIRED", "A file part is required.");
            }
            DocumentFieldsInput input;
            try {
                input = jsonMapper.readValue(json, DocumentFieldsInput.class);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "METADATA_INVALID", "The metadata part is not valid JSON.");
            }
            return uploads.upload(input, file.fileName(), file.contentType(), file.stream(), idempotencyKey);
        });
        return ResponseEntity.status(outcome.status()).location(URI.create("/api/v1/documents/" + outcome.documentId()))
                .body(outcome.body());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentDetailView> get(@PathVariable UUID id) {
        DocumentDetailView view = queries.view(id);
        return ResponseEntity.ok().eTag(ETags.of(view.document().version())).body(view);
    }

    /** Soft delete to the recycle bin (200), or a pending change when the type requires approval (202). */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<?> delete(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        DocumentDeletionService.DeleteOutcome outcome = deletions.delete(id);
        if (outcome.pendingChange() != null) {
            return ResponseEntity.accepted().location(URI.create("/api/v1/admin/pending-changes/"
                    + outcome.pendingChange().id())).body(outcome.pendingChange());
        }
        return ResponseEntity.ok(outcome.document());
    }

    /** From the recycle bin back to the previous status ({@code RESTORE}). */
    @PostMapping("/{id}/restore")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentView> restore(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        DocumentView view = deletions.restore(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    /** Approval records of the document ({@code VIEW_METADATA}). */
    @GetMapping("/{id}/history")
    @PreAuthorize("isAuthenticated()")
    List<ApprovalRecordView> history(@PathVariable UUID id) {
        return queries.history(id);
    }

    /** Audit events of the document ({@code VIEW_AUDIT}). */
    @GetMapping("/{id}/audit")
    @PreAuthorize("isAuthenticated()")
    List<AuditEventView> audit(@PathVariable UUID id) {
        return queries.audit(id);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    PageResponse<DocumentView> list(@RequestParam(required = false) UUID cabinetId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID folderId, @RequestParam(required = false) String cif,
            @RequestParam(required = false) String account, @RequestParam(required = false) DocumentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return PageResponse.of(queries.list(new DocumentFilter(type, folderId, cif, account, null, null, status,
                cabinetId),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), view -> view);
    }

    /**
     * Merge patch of the metadata (RFC 7396). 200 with the document when applied; 202 with the pending change when
     * the document type requires a checker's approval for metadata changes.
     */
    @PatchMapping(path = "/{id}", consumes = {"application/merge-patch+json", MediaType.APPLICATION_JSON_VALUE})
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<?> patch(@PathVariable UUID id, @RequestBody JsonNode patch,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        DocumentMetadataService.ChangeOutcome outcome = metadata.change(id, ETags.requiredVersion(ifMatch), patch);
        if (outcome.pendingChange() != null) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/admin/pending-changes/" + outcome.pendingChange().id()))
                    .body(outcome.pendingChange());
        }
        return ResponseEntity.ok().eTag(ETags.of(outcome.document().version())).body(outcome.document());
    }
}
