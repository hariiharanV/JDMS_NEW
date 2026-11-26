package com.jmr.jdms.document.web;

import com.jmr.jdms.document.domain.DocumentContentService;
import com.jmr.jdms.document.domain.DocumentDeletionService;
import com.jmr.jdms.document.domain.DocumentFieldsInput;
import com.jmr.jdms.document.domain.DocumentQueryService.DocumentFilter;
import com.jmr.jdms.document.domain.DocumentQueryService;
import com.jmr.jdms.document.domain.DocumentUploadService;
import com.jmr.jdms.document.domain.DocumentVersioningService;
import com.jmr.jdms.document.domain.DocumentView;
import com.jmr.jdms.document.domain.UploadOutcome;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration API for bank systems (FLEXCUBE, LOS, CRM; spec §4.10, {@code /integration/v1}). Business keys
 * instead of UI fields; the legacy JDMS request fields map onto it (06-legacy-jdms-reference §4). The caller is the
 * OAuth2 client and only the client: user or role IDs in a request are refused, never trusted. Clients only,
 * rate-limited and audited per call by the integration module; documents are checked per action like any caller.
 */
@RestController
@RequestMapping("/integration/v1/documents")
@PreAuthorize("principal.principalType().name() == 'CLIENT'")
class IntegrationDocumentController {

    static final Pattern EXTERNAL_SYSTEM = Pattern.compile("[A-Za-z0-9_.-]{1,30}");
    /** Legacy header fields that carried caller-chosen identities (D-09: no impersonation). */
    static final Set<String> IMPERSONATION_FIELDS = Set.of("user_id", "userId", "role_id", "roleId");

    private final DocumentUploadService uploads;
    private final DocumentQueryService queries;
    private final DocumentContentService content;
    private final DocumentVersioningService versioning;
    private final JsonMapper jsonMapper;
    private final MetadataCatalog catalog;
    private final DocumentDeletionService deletions;

    IntegrationDocumentController(DocumentUploadService uploads, DocumentQueryService queries,
            DocumentContentService content, DocumentVersioningService versioning, JsonMapper jsonMapper,
            MetadataCatalog catalog, DocumentDeletionService deletions) {
        this.uploads = uploads;
        this.queries = queries;
        this.content = content;
        this.versioning = versioning;
        this.jsonMapper = jsonMapper;
        this.catalog = catalog;
        this.deletions = deletions;
    }

    /** Upload by business keys; see {@link IntegrationUpload}. 201 with {@code documentId} and {@code status}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<JsonNode> upload(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UploadOutcome outcome = StreamingMultipart.read(request, (fields, file) -> {
            IntegrationUpload metadata = metadata(fields.get("metadata"));
            if (file == null) {
                throw ApiException.unprocessable("FILE_REQUIRED", "A file part is required.");
            }
            return uploads.upload(metadata.toFields(hasIssueDate(metadata.documentTypeCode()),
                    folderOf(metadata)), file.fileName(),
                    file.contentType(), file.stream(), idempotencyKey, metadata.externalSystem());
        });
        JsonNode body = outcome.body();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId", body.get("id").asString());
        response.put("versionId", body.at("/currentVersion/id").asString());
        response.put("status", body.get("status").asString());
        response.put("warnings", body.get("warnings"));
        return ResponseEntity.status(outcome.status())
                .location(URI.create("/integration/v1/documents/" + outcome.documentId()))
                .body(jsonMapper.valueToTree(response));
    }

    /** List by business keys; at least one key is required (no browsing through the integration API). */
    @GetMapping
    PageResponse<DocumentView> list(@RequestParam(required = false) String cif,
            @RequestParam(required = false) String accountNo, @RequestParam(required = false) String contractRef,
            @RequestParam(required = false) String applicationRef,
            @RequestParam(required = false) String externalRef,
            @RequestParam(required = false) String documentTypeCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        if (cif == null && accountNo == null && contractRef == null && applicationRef == null && externalRef == null) {
            throw new ValidationFailedException("A business key is required.", List.of(new Violation("cif",
                    "BUSINESS_KEY_REQUIRED",
                    "Give at least one of cif, accountNo, contractRef, applicationRef, externalRef.")));
        }
        return PageResponse.of(queries.list(new DocumentFilter(documentTypeCode, null, cif, accountNo, contractRef,
                applicationRef, null, null, externalRef), PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
                view -> view);
    }

    @GetMapping("/{id}")
    DocumentView get(@PathVariable UUID id) {
        return queries.view(id).document();
    }

    /** The current version's content (approved, or the only version while the first one awaits approval). */
    @GetMapping("/{id}/content")
    void content(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        UUID current = currentVersion(id);
        DocumentContentService.Download download = content.open(id, current);
        DocumentVersionController.send(download, response, id, current);
    }

    @GetMapping("/{id}/preview")
    void preview(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        UUID current = currentVersion(id);
        DocumentVersionController.sendInline(content.preview(id, current), "preview", id, current, response);
    }

    /** A new major version in one call (multipart {@code comment} then {@code file}); {@code Idempotency-Key}. */
    @PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<JsonNode> newVersion(@PathVariable UUID id, HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UploadOutcome outcome = StreamingMultipart.read(request, (fields, file) -> {
            if (file == null) {
                throw ApiException.unprocessable("FILE_REQUIRED", "A file part is required.");
            }
            return versioning.newVersion(id, file.stream(), file.fileName(), file.contentType(), fields.get("comment"),
                    idempotencyKey);
        });
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /** Soft delete (200), or 202 with the pending change when the type requires approval. */
    @DeleteMapping("/{id}")
    ResponseEntity<?> delete(@PathVariable UUID id) {
        DocumentDeletionService.DeleteOutcome outcome = deletions.delete(id);
        return outcome.pendingChange() != null ? ResponseEntity.accepted().body(outcome.pendingChange())
                : ResponseEntity.ok(outcome.document());
    }

    @ExceptionHandler(DocumentVersionController.DownloadAbortedException.class)
    void downloadAborted(HttpServletResponse response) throws IOException {
        response.flushBuffer();
    }

    /**
     * The folder named by {@code folderPath} in the type's cabinet (B-18), or {@code folderId}; both together must
     * agree. An unknown path is 422 {@code FOLDER_INVALID}, before anything is stored.
     */
    private UUID folderOf(IntegrationUpload metadata) {
        if (metadata.folderPath() == null || metadata.folderPath().isBlank()) {
            return metadata.folderId();
        }
        UUID byPath = Optional.ofNullable(metadata.documentTypeCode()).flatMap(catalog::findDocumentType)
                .flatMap(type -> catalog.folderByPath(type.cabinetId(), metadata.folderPath())).orElse(null);
        if (byPath == null || (metadata.folderId() != null && !metadata.folderId().equals(byPath))) {
            throw new ValidationFailedException("The document metadata is invalid.", List.of(new Violation(
                    "folderPath", "FOLDER_INVALID", byPath == null ? "No folder with this path in the type's cabinet."
                            : "folderPath and folderId name different folders.")));
        }
        return byPath;
    }

    /** Legacy callers send an issue date for every type; it is kept where the type has an {@code issue_date}. */
    private boolean hasIssueDate(String documentTypeCode) {
        return documentTypeCode != null && catalog.findDocumentType(documentTypeCode)
                .map(type -> catalog.fields(type.id()).stream().anyMatch(field -> field.key().equals("issue_date")))
                .orElse(false);
    }

    private UUID currentVersion(UUID id) {
        DocumentView document = queries.get(id);
        if (document.currentVersion() == null) {
            throw new ApiException(HttpStatus.CONFLICT, DocumentContentService.CONTENT_UNAVAILABLE,
                    "The document has no current version.");
        }
        return document.currentVersion().id();
    }

    private IntegrationUpload metadata(String json) {
        if (json == null) {
            throw ApiException.unprocessable("METADATA_REQUIRED", "A metadata part must come before the file.");
        }
        JsonNode tree;
        IntegrationUpload metadata;
        try {
            tree = jsonMapper.readTree(json);
            metadata = jsonMapper.treeToValue(tree, IntegrationUpload.class);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "METADATA_INVALID", "The metadata part is not valid JSON.");
        }
        List<Violation> violations = new ArrayList<>();
        IMPERSONATION_FIELDS.stream().filter(tree::has).sorted().forEach(field -> violations.add(new Violation(field,
                "FIELD_NOT_ALLOWED", "Identities come from the OAuth2 client, never from the request.")));
        if (metadata.externalSystem() == null || !EXTERNAL_SYSTEM.matcher(metadata.externalSystem()).matches()) {
            violations.add(new Violation("externalSystem", "EXTERNAL_SYSTEM_INVALID",
                    "Required: 1-30 letters, digits, '_', '-' or '.'."));
        }
        if (metadata.cif() != null && metadata.customerNo() != null && !metadata.cif().equals(metadata.customerNo())) {
            violations.add(new Violation("customerNo", "CUSTOMER_MISMATCH", "cif and customerNo name different "
                    + "customers."));
        }
        ValidationFailedException.throwIfAny("The document metadata is invalid.", violations);
        return metadata;
    }

    /**
     * The integration upload request. Legacy mapping: {@code appln_id} → {@code cabinetCode}, {@code doc_type} →
     * {@code documentTypeCode}, {@code cifNumber}/{@code customerId} → {@code cif} ({@code customerNo} is accepted
     * as the same key), {@code contractRefNo} → {@code contractRef}, {@code transactionRefNo} → {@code
     * transactionRef}, {@code applicationRefNo} → {@code applicationRef}, {@code external_system} → {@code
     * externalSystem}; {@code issueDate} fills the type's {@code issue_date} field if it has one; {@code
     * functionId}/{@code keyId} → {@code customFields.fcFunctionId}/{@code fcKeyId}. The title defaults to the type
     * and the main business key.
     */
    record IntegrationUpload(String externalSystem, String cabinetCode, String documentTypeCode, String title,
            Classification classification, String branchCode, UUID folderId, String folderPath, String cif,
            String customerNo,
            String customerName, String accountNo, String collateralCode, String contractRef, String transactionRef,
            String applicationRef, String externalRef, LocalDate issueDate, LocalDate expiryDate,
            Map<String, Object> customFields) {

        DocumentFieldsInput toFields(boolean keepIssueDate, UUID folder) {
            String customer = cif != null ? cif : customerNo;
            Map<String, Object> custom = new LinkedHashMap<>(customFields == null ? Map.of() : customFields);
            if (issueDate != null && keepIssueDate) {
                custom.putIfAbsent("issue_date", issueDate.toString());
            }
            String key = firstNonNull(contractRef, accountNo, applicationRef, collateralCode, customer, transactionRef);
            String effectiveTitle = title != null && !title.isBlank() ? title
                    : documentTypeCode + (key == null ? "" : " " + key);
            return new DocumentFieldsInput(cabinetCode, documentTypeCode, effectiveTitle, classification, branchCode,
                    folder, customer, customerName, accountNo, collateralCode, contractRef, transactionRef,
                    applicationRef, expiryDate, null, null, externalRef, custom);
        }

        private static String firstNonNull(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }
    }
}
