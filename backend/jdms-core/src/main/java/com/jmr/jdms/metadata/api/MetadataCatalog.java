package com.jmr.jdms.metadata.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only lookups of the current tenant's metadata model for other modules. */
public interface MetadataCatalog {

    Optional<DocumentTypeInfo> findDocumentType(String code);

    Optional<DocumentTypeInfo> findDocumentType(UUID id);

    /** Codes of cabinets by ID, for display. */
    Optional<String> cabinetCode(UUID cabinetId);

    /** Whether the folder exists and belongs to the cabinet. */
    boolean folderInCabinet(UUID folderId, UUID cabinetId);

    /** The folder and its ancestors, nearest first; empty for null. Used for folder-scoped access rules. */
    List<UUID> folderPath(UUID folderId);

    /**
     * The folder at a path of folder names in a cabinet ({@code /Customer onboarding/KYC}; names compared exactly,
     * leading and trailing slashes optional), for callers that name folders as staff see them (B-18).
     */
    Optional<UUID> folderByPath(UUID cabinetId, String path);

    /** The folder and all its sub-folders. */
    Set<UUID> folderSubtree(UUID folderId);

    /** Custom fields of a document type. */
    List<FieldInfo> fields(UUID documentTypeId);

    /** Custom fields of all document types of the tenant. */
    List<FieldInfo> allFields();
}
