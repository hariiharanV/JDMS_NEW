package com.jmr.jdms.document.web;

import com.jmr.jdms.document.domain.CaptureService;
import com.jmr.jdms.document.domain.CaptureService.CaptureInput;
import com.jmr.jdms.document.domain.CaptureService.CaptureView;
import com.jmr.jdms.document.domain.CaptureService.StartOutcome;
import com.jmr.jdms.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /captures}: assisted indexing (B-15, ADR-0024). A file attached on the upload screen is read in the
 * background and the values found are suggested for the form; the upload itself stays {@code POST /documents}.
 */
@RestController
@RequestMapping("/api/v1/captures")
class CaptureController {

    private final CaptureService captures;
    private final JsonMapper jsonMapper;

    CaptureController(CaptureService captures, JsonMapper jsonMapper) {
        this.captures = captures;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Multipart: a {@code metadata} part ({@code documentTypeCode}, {@code branchCode}, optional {@code folderId} and
     * {@code classification}) followed by one {@code file} part. 202: the file is being read; poll the location.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<JsonNode> start(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        StartOutcome outcome = StreamingMultipart.read(request, (fields, file) -> {
            String json = fields.get("metadata");
            if (json == null) {
                throw ApiException.unprocessable("METADATA_REQUIRED", "A metadata part must come before the file.");
            }
            if (file == null) {
                throw ApiException.unprocessable("FILE_REQUIRED", "A file part is required.");
            }
            CaptureInput input;
            try {
                input = jsonMapper.readValue(json, CaptureInput.class);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "METADATA_INVALID", "The metadata part is not valid JSON.");
            }
            return captures.start(input, file.stream(), idempotencyKey);
        });
        return ResponseEntity.status(outcome.status()).location(URI.create("/api/v1/captures/" + outcome.captureId()))
                .body(outcome.body());
    }

    /** Status and, once read, the suggested values. Only the user who attached the file sees it. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<CaptureView> get(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(captures.view(id));
    }

    /** Deletes the file (if not yet read) and the suggestions. */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> discard(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        captures.discard(id);
        return ResponseEntity.noContent().build();
    }
}
