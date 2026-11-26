package com.jmr.jdms.metadata.api;

import java.util.UUID;

/**
 * A custom field as other modules need it (search indexing, facets).
 *
 * @param dataType {@code TEXT}, {@code NUMBER}, {@code DATE}, {@code BOOLEAN} or {@code LOV}
 */
public record FieldInfo(UUID documentTypeId, String key, String dataType, boolean searchable, boolean facet,
        boolean sensitive) {
}
