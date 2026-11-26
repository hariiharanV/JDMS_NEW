package com.jmr.jdms.metadata.api;

import java.util.Map;
import java.util.UUID;

/** Validates and normalises a document's {@code custom_fields} against its document type's field definitions. */
public interface CustomFieldValidation {

    /**
     * Checks type, mandatory, length and LOV membership of every field, applies defaults and rejects unknown keys.
     *
     * @throws com.jmr.jdms.shared.error.ApiException 404 {@code DOCUMENT_TYPE_NOT_FOUND} when the type does not exist
     *                                                in the current tenant
     */
    CustomFieldsResult validate(UUID documentTypeId, Map<String, Object> customFields);
}
