package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.CustomFieldValidation;
import com.jmr.jdms.metadata.api.CustomFieldsResult;
import com.jmr.jdms.shared.error.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomFieldValidationService implements CustomFieldValidation {

    static final String DOCUMENT_TYPE_NOT_FOUND = "DOCUMENT_TYPE_NOT_FOUND";

    private final DocumentTypeRepository documentTypes;
    private final FieldDefinitionRepository fields;
    private final LovCatalog lovCatalog;

    CustomFieldValidationService(DocumentTypeRepository documentTypes, FieldDefinitionRepository fields,
            LovCatalog lovCatalog) {
        this.documentTypes = documentTypes;
        this.fields = fields;
        this.lovCatalog = lovCatalog;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomFieldsResult validate(UUID documentTypeId, Map<String, Object> customFields) {
        if (!documentTypes.existsById(documentTypeId)) {
            throw ApiException.notFound(DOCUMENT_TYPE_NOT_FOUND, "Document type not found.");
        }
        var specs = fields.findByDocumentTypeId(documentTypeId).stream().map(FieldDefinition::spec).toList();
        Map<String, Set<String>> lovValues = lovCatalog.activeValues(specs.stream()
                .map(FieldSpec::lovCode).filter(java.util.Objects::nonNull).toList());
        return CustomFieldValidator.validate(specs, code -> lovValues.getOrDefault(code, Set.of()), customFields);
    }
}
