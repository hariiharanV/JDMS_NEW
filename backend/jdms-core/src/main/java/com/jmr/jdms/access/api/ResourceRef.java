package com.jmr.jdms.access.api;

import com.jmr.jdms.shared.Classification;
import java.util.List;
import java.util.UUID;

/**
 * What is accessed, with the attributes the policy needs (callers load them; the access module does not read other
 * modules' data). {@code folderPath} is the document's folder and its ancestors, nearest first.
 */
public sealed interface ResourceRef {

    UUID tenantId();

    /** Tenant-wide actions: administration, audit, records management. */
    record Tenant(UUID tenantId) implements ResourceRef {
    }

    /** A document to be created (UPLOAD), with its intended placement. */
    record NewDocument(UUID tenantId, UUID cabinetId, UUID documentTypeId, List<UUID> folderPath, UUID branchId,
            Classification classification) implements ResourceRef {

        public NewDocument {
            folderPath = folderPath == null ? List.of() : List.copyOf(folderPath);
        }
    }

    record Document(UUID tenantId, UUID documentId, UUID cabinetId, UUID documentTypeId, List<UUID> folderPath,
            UUID branchId, Classification classification, boolean onLegalHold, boolean recordDeclared)
            implements ResourceRef {

        public Document {
            folderPath = folderPath == null ? List.of() : List.copyOf(folderPath);
        }
    }
}
