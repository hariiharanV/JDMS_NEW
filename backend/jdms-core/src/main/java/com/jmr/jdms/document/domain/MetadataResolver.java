package com.jmr.jdms.document.domain;

import com.jmr.jdms.metadata.api.CustomFieldValidation;
import com.jmr.jdms.metadata.api.CustomFieldsResult;
import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import com.jmr.jdms.metadata.api.StandardFields;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.context.PrincipalType;
import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.tenant.api.BranchDirectory;
import com.jmr.jdms.tenant.api.BranchInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns client metadata into validated {@link DocumentMetadata} for a document type: standard fields, branch,
 * folder, classification, required standard fields and custom fields (through the metadata module's validation
 * engine). All problems are reported together (422 with {@code errors}); messages never echo values.
 */
@Component
public class MetadataResolver {

    public static final String DOCUMENT_TYPE_UNKNOWN = "DOCUMENT_TYPE_UNKNOWN";

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    private final MetadataCatalog catalog;
    private final BranchDirectory branches;
    private final CustomFieldValidation customFieldValidation;

    MetadataResolver(MetadataCatalog catalog, BranchDirectory branches, CustomFieldValidation customFieldValidation) {
        this.catalog = catalog;
        this.branches = branches;
        this.customFieldValidation = customFieldValidation;
    }

    /** The active document type for an upload; 422 when unknown, inactive or not open to the caller's channel. */
    @Transactional(readOnly = true)
    public DocumentTypeInfo uploadableType(DocumentFieldsInput input) {
        List<Violation> violations = new ArrayList<>();
        DocumentTypeInfo type = input.documentTypeCode() == null ? null
                : catalog.findDocumentType(input.documentTypeCode()).orElse(null);
        if (type == null || !type.active() || !type.cabinetActive()) {
            violations.add(new Violation("documentTypeCode", DOCUMENT_TYPE_UNKNOWN,
                    "Unknown or inactive document type."));
        } else {
            if (input.cabinetCode() != null && !input.cabinetCode().equals(type.cabinetCode())) {
                violations.add(new Violation("cabinetCode", "CABINET_MISMATCH",
                        "The document type belongs to another cabinet."));
            }
            String channel = RequestContext.require().principalType() == PrincipalType.CLIENT
                    ? DocumentTypeInfo.CHANNEL_INTEGRATION : DocumentTypeInfo.CHANNEL_UI;
            if (!type.channels().contains(channel)) {
                violations.add(new Violation("documentTypeCode", "CHANNEL_NOT_ALLOWED",
                        "Documents of this type cannot be uploaded through this channel."));
            }
        }
        ValidationFailedException.throwIfAny("The document metadata is invalid.", violations);
        return type;
    }

    @Transactional(readOnly = true)
    public DocumentMetadata resolve(DocumentTypeInfo type, DocumentFieldsInput input, String fallbackTitle) {
        List<Violation> violations = new ArrayList<>();
        RequestContext caller = RequestContext.require();

        String title = text("title", input.title() == null || input.title().isBlank() ? fallbackTitle : input.title(),
                500, violations);
        if (title == null) {
            violations.add(new Violation("title", "TITLE_REQUIRED", "A title is required."));
        }

        Classification classification = input.classification() == null ? type.defaultClassification()
                : input.classification();
        if (!classification.covers(type.defaultClassification())) {
            violations.add(new Violation("classification", "CLASSIFICATION_BELOW_DEFAULT",
                    "Classification cannot be lower than the type's default (" + type.defaultClassification() + ")."));
        }
        if (!caller.clearance().covers(classification)) {
            violations.add(new Violation("classification", "CLASSIFICATION_ABOVE_CLEARANCE",
                    "You cannot classify a document above your own clearance."));
        }

        UUID branchId = null;
        if (input.branchCode() == null || input.branchCode().isBlank()) {
            violations.add(new Violation("branchCode", "BRANCH_REQUIRED", "A branch is required."));
        } else {
            branchId = branches.findByCodes(List.of(input.branchCode())).stream().map(BranchInfo::id).findFirst()
                    .orElse(null);
            if (branchId == null) {
                violations.add(new Violation("branchCode", "BRANCH_UNKNOWN", "Unknown branch."));
            }
        }

        if (input.folderId() != null && !catalog.folderInCabinet(input.folderId(), type.cabinetId())) {
            violations.add(new Violation("folderId", "FOLDER_INVALID", "The folder does not exist in the cabinet."));
        }

        StandardMetadata standard = new StandardMetadata(
                text(StandardFields.CIF, input.cif(), 50, violations),
                text(StandardFields.CUSTOMER_NAME, input.customerName(), 200, violations),
                text(StandardFields.ACCOUNT_NO, input.accountNo(), 50, violations),
                text(StandardFields.COLLATERAL_CODE, input.collateralCode(), 50, violations),
                text(StandardFields.CONTRACT_REF, input.contractRef(), 50, violations),
                text(StandardFields.TRANSACTION_REF, input.transactionRef(), 50, violations),
                text(StandardFields.APPLICATION_REF, input.applicationRef(), 50, violations),
                input.expiryDate(),
                text(StandardFields.PHYSICAL_LOCATION, input.physicalLocation(), 200, violations),
                text(StandardFields.PHYSICAL_REF, input.physicalRef(), 100, violations));
        Map<String, Object> present = presence(standard, input.branchCode());
        type.requiredStandardFields().stream().sorted().filter(field -> present.get(field) == null)
                .forEach(field -> violations.add(new Violation(field, "FIELD_REQUIRED",
                        "This field is required for the document type.")));

        CustomFieldsResult custom = customFieldValidation.validate(type.id(), input.customFields());
        custom.violations().forEach(violation -> violations.add(new Violation("customFields." + violation.field(),
                violation.code(), violation.message())));

        String externalRef = text("externalRef", input.externalRef(), 100, violations);
        ValidationFailedException.throwIfAny("The document metadata is invalid.", violations);
        return new DocumentMetadata(title, input.folderId(), classification, branchId, standard,
                CustomFieldsCodec.write(custom.values()), externalRef);
    }

    private static Map<String, Object> presence(StandardMetadata s, String branchCode) {
        Map<String, Object> present = new java.util.HashMap<>();
        present.put(StandardFields.CIF, s.cif());
        present.put(StandardFields.CUSTOMER_NAME, s.customerName());
        present.put(StandardFields.ACCOUNT_NO, s.accountNo());
        present.put(StandardFields.COLLATERAL_CODE, s.collateralCode());
        present.put(StandardFields.CONTRACT_REF, s.contractRef());
        present.put(StandardFields.TRANSACTION_REF, s.transactionRef());
        present.put(StandardFields.APPLICATION_REF, s.applicationRef());
        present.put(StandardFields.EXPIRY_DATE, s.expiryDate());
        present.put(StandardFields.PHYSICAL_LOCATION, s.physicalLocation());
        present.put(StandardFields.PHYSICAL_REF, s.physicalRef());
        present.put(StandardFields.BRANCH_CODE, branchCode);
        return present;
    }

    /** Trimmed text, null when blank; reports control characters and over-long values. */
    private static String text(String field, String value, int max, List<Violation> violations) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (CONTROL.matcher(trimmed).find()) {
            violations.add(new Violation(field, "FIELD_INVALID_CHARACTERS", "Contains control characters."));
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            violations.add(new Violation(field, "FIELD_TOO_LONG", "Must be at most " + max + " characters."));
            return null;
        }
        return trimmed;
    }
}
