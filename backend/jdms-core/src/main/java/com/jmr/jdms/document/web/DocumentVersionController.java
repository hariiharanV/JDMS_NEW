package com.jmr.jdms.document.web;

import com.jmr.jdms.document.domain.DocumentContentService;
import com.jmr.jdms.document.domain.DocumentQueryService;
import com.jmr.jdms.document.domain.DocumentVersioningService;
import com.jmr.jdms.document.domain.DocumentView;
import com.jmr.jdms.document.domain.VersionView;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.web.ETags;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Versions, content download and check-out / check-in (spec §4.3, §4.5). */
@RestController
@RequestMapping("/api/v1/documents/{documentId}")
class DocumentVersionController {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionController.class);

    private final DocumentQueryService queries;
    private final DocumentVersioningService versioning;
    private final DocumentContentService content;
    private final JsonMapper jsonMapper;

    DocumentVersionController(DocumentQueryService queries, DocumentVersioningService versioning,
            DocumentContentService content, JsonMapper jsonMapper) {
        this.queries = queries;
        this.versioning = versioning;
        this.content = content;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/versions")
    @PreAuthorize("isAuthenticated()")
    List<VersionView> versions(@PathVariable UUID documentId) {
        return queries.versions(documentId);
    }

    /** A draft (minor) version while the document is checked out by the caller: {@code comment} then {@code file}. */
    @PostMapping(path = "/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<VersionView> uploadDraft(@PathVariable UUID documentId, HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        VersionView view = StreamingMultipart.read(request, (fields, file) -> {
            if (file == null) {
                throw ApiException.unprocessable("FILE_REQUIRED", "A file part is required.");
            }
            return versioning.uploadDraft(documentId, file.stream(), file.fileName(), file.contentType(),
                    fields.get("comment"), idempotencyKey);
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Streams the original file. Always sent as an attachment of type {@code application/octet-stream}, so browsers
     * never render uploaded content.
     */
    @GetMapping("/versions/{versionId}/content")
    @PreAuthorize("isAuthenticated()")
    void download(@PathVariable UUID documentId, @PathVariable UUID versionId, HttpServletResponse response)
            throws IOException {
        send(content.open(documentId, versionId), response, documentId, versionId);
    }

    /** Streams a download as an {@code application/octet-stream} attachment (also used by the integration API). */
    static void send(DocumentContentService.Download download, HttpServletResponse response, UUID documentId,
            UUID versionId) throws IOException {
        try (var verified = download.content()) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setContentLengthLong(download.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(download.fileName() == null ? "document" : download.fileName(), StandardCharsets.UTF_8)
                    .build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            OutputStream out = response.getOutputStream();
            try {
                verified.writeTo(out);
            } catch (IOException e) {
                // integrity failure already reported (version MISSING, audit, alert); the response is cut short of its
                // Content-Length, so the client sees a failed transfer, never a complete wrong file
                log.warn("Download of document {} version {} aborted: {}", documentId, versionId,
                        String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
                throw new DownloadAbortedException();
            }
        }
    }

    /** Ends a download whose response has already started; handled without a body and without a stack trace. */
    static final class DownloadAbortedException extends RuntimeException {

        DownloadAbortedException() {
            super("Download aborted", null, false, false);
        }
    }

    /** The preview (PDF rendition, or a PDF or image original as detected by the pipeline), shown inline. */
    @GetMapping("/versions/{versionId}/preview")
    @PreAuthorize("isAuthenticated()")
    void preview(@PathVariable UUID documentId, @PathVariable UUID versionId, HttpServletResponse response)
            throws IOException {
        sendInline(content.preview(documentId, versionId), "preview", documentId, versionId, response);
    }

    @GetMapping("/versions/{versionId}/thumbnail")
    @PreAuthorize("isAuthenticated()")
    void thumbnail(@PathVariable UUID documentId, @PathVariable UUID versionId, HttpServletResponse response)
            throws IOException {
        sendInline(content.thumbnail(documentId, versionId), "thumbnail", documentId, versionId, response);
    }

    /** Inline, with a type set by the pipeline; sandboxed so a viewer can never run anything it contains. */
    static void sendInline(DocumentContentService.Inline inline, String name, UUID documentId,
            UUID versionId, HttpServletResponse response) throws IOException {
        try (var verified = inline.content()) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(inline.mimeType());
            response.setContentLengthLong(inline.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build()
                    .toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            try {
                verified.writeTo(response.getOutputStream());
            } catch (IOException e) {
                log.warn("{} of document {} version {} aborted", name, documentId, versionId);
                throw new DownloadAbortedException();
            }
        }
    }


    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentView> checkOut(@PathVariable UUID documentId) {
        return withETag(versioning.checkOut(documentId));
    }

    @PostMapping("/cancel-checkout")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentView> cancelCheckOut(@PathVariable UUID documentId) {
        return withETag(versioning.cancelCheckOut(documentId));
    }

    /**
     * Check-in: multipart with an optional {@code comment} and an optional {@code file}, or JSON
     * {@code {"comment": "..."}}. Without a file the latest draft becomes the new major version.
     */
    @PostMapping("/checkin")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentView> checkIn(@PathVariable UUID documentId, HttpServletRequest request)
            throws IOException {
        if (StreamingMultipart.isMultipart(request)) {
            return withETag(StreamingMultipart.read(request, (fields, file) -> versioning.checkIn(documentId,
                    file == null ? null : file.stream(), file == null ? null : file.fileName(),
                    file == null ? null : file.contentType(), fields.get("comment"))));
        }
        byte[] body = request.getInputStream().readNBytes(64 * 1024);
        JsonNode json = body.length == 0 ? null : jsonMapper.readTree(body);
        String comment = json != null && json.hasNonNull("comment") ? json.get("comment").asString() : null;
        return withETag(versioning.checkIn(documentId, null, null, null, comment));
    }

    @PostMapping("/versions/{versionId}/restore")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<DocumentView> restore(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        return withETag(versioning.restore(documentId, versionId));
    }

    @ExceptionHandler(DownloadAbortedException.class)
    void downloadAborted(HttpServletResponse response) throws IOException {
        response.flushBuffer();
    }

    private static ResponseEntity<DocumentView> withETag(DocumentView view) {
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
