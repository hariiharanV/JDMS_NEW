package com.jmr.jdms.document.web;

import com.jmr.jdms.access.api.AccessProvisioning.ImportedRule;
import com.jmr.jdms.access.api.Action;
import com.jmr.jdms.document.domain.DocumentFieldsInput;
import com.jmr.jdms.document.domain.DocumentImportService;
import com.jmr.jdms.document.domain.DocumentImportService.ImportRequest;
import com.jmr.jdms.document.domain.DocumentImportService.ImportResult;
import com.jmr.jdms.document.domain.DocumentImportService.ImportedVersion;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.workflow.api.ApprovalDecision;
import com.jmr.jdms.workflow.api.Approvals.ImportedDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Import of legacy JDMS documents for the migration tool (ADR-0020, {@code /integration/v1/migration}). Clients only;
 * {@code IMPORT} is checked by the service. Rate-limited and audited per call like every integration call.
 */
@RestController
@RequestMapping("/integration/v1/migration")
@PreAuthorize("principal.principalType().name() == 'CLIENT'")
class MigrationController {

    private final DocumentImportService imports;
    private final JsonMapper jsonMapper;

    MigrationController(DocumentImportService imports, JsonMapper jsonMapper) {
        this.imports = imports;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Multipart: a {@code metadata} part ({@link ImportMetadata}), then one {@code file} part per version, oldest
     * first. 201 with what was created, or 200 when an earlier run imported the document (its files are not read).
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportResult> importDocument(HttpServletRequest request) {
        ImportResult result = StreamingMultipart.readFiles(request, fields -> {
            DocumentImportService.Import run = imports.start(metadata(fields.get("metadata")).toRequest());
            return new StreamingMultipart.FileSink<>() {
                @Override
                public boolean wantsFiles() {
                    return run.wantsFiles();
                }

                @Override
                public void accept(StreamingMultipart.FilePart file) {
                    run.store(file.fileName(), file.stream());
                }

                @Override
                public ImportResult finish() {
                    return run.complete();
                }
            };
        });
        return ResponseEntity.status(result.alreadyImported() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    /** The actions a user with these roles, branches and clearance would have on each document (not audited). */
    @PostMapping("/access-check")
    Map<UUID, List<Action>> accessCheck(@Valid @RequestBody AccessCheck check) {
        return imports.accessCheck(check.roles(), check.allBranches(), check.branchCodes() == null ? Set.of()
                : check.branchCodes(), check.clearance(), check.documentIds());
    }

    record AccessCheck(@NotNull @Size(max = 50) Set<String> roles, boolean allBranches,
            @Size(max = 500) Set<String> branchCodes, @NotNull Classification clearance,
            @NotNull @Size(min = 1, max = 500) List<UUID> documentIds) {
    }

    private ImportMetadata metadata(String json) {
        if (json == null) {
            throw ApiException.unprocessable("METADATA_REQUIRED", "A metadata part must come before the files.");
        }
        try {
            return jsonMapper.readValue(json, ImportMetadata.class);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "METADATA_INVALID", "The metadata part is not valid JSON.");
        }
    }

    /** The legacy document as the migration tool maps it (reference §5). */
    record ImportMetadata(String legacyDocumentId, String status, Instant createdAt, String createdBy,
            String documentTypeCode, String title, Classification classification, String branchCode, UUID folderId,
            String cif, String customerName, String accountNo, String collateralCode, String contractRef,
            String transactionRef, String applicationRef, LocalDate expiryDate, String physicalLocation,
            String physicalRef, Map<String, Object> customFields, List<VersionPart> versions,
            List<HistoryStep> history, List<Permission> permissions) {

        ImportRequest toRequest() {
            DocumentFieldsInput fields = new DocumentFieldsInput(null, documentTypeCode, title, classification,
                    branchCode, folderId, cif, customerName, accountNo, collateralCode, contractRef, transactionRef,
                    applicationRef, expiryDate, physicalLocation, physicalRef, null, customFields);
            return new ImportRequest(legacyDocumentId, fields, status, createdAt, createdBy,
                    versions == null ? List.of() : versions.stream().map(version -> new ImportedVersion(
                            version.fileName(), version.mimeType(), version.sha256(), version.createdAt(),
                            version.createdBy(), version.comment())).toList(),
                    history == null ? List.of() : history.stream().map(step -> new ImportedDecision(step.round(),
                            step.stage(), step.actor(), step.decision(), step.comment(), step.decidedAt())).toList(),
                    permissions == null ? List.of() : permissions.stream().map(permission -> new ImportedRule(
                            permission.role(), permission.actions() == null ? Set.of() : permission.actions(),
                            "Migrated from legacy JDMS" + (permission.legacyRole() == null ? ""
                                    : " (legacy role " + permission.legacyRole() + ")"))).toList());
        }
    }

    record VersionPart(String fileName, String mimeType, String sha256, Instant createdAt, String createdBy,
            String comment) {
    }

    record HistoryStep(int round, String stage, String actor, ApprovalDecision decision, String comment,
            Instant decidedAt) {
    }

    record Permission(String role, String legacyRole, Set<Action> actions) {
    }
}
