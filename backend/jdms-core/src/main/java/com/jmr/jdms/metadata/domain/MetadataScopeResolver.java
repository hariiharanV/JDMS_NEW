package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.access.api.ScopeResolver;
import com.jmr.jdms.access.api.ScopeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Validates and labels cabinet, document type and folder scopes of access rules. */
@Component
class MetadataScopeResolver implements ScopeResolver {

    private final CabinetRepository cabinets;
    private final DocumentTypeRepository documentTypes;
    private final FolderRepository folders;

    MetadataScopeResolver(CabinetRepository cabinets, DocumentTypeRepository documentTypes, FolderRepository folders) {
        this.cabinets = cabinets;
        this.documentTypes = documentTypes;
        this.folders = folders;
    }

    @Override
    public boolean supports(ScopeType type) {
        return type == ScopeType.CABINET || type == ScopeType.DOCUMENT_TYPE || type == ScopeType.FOLDER;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> describe(ScopeType type, UUID id) {
        return switch (type) {
            case CABINET -> cabinets.findById(id).map(cabinet -> "Cabinet " + cabinet.getCode());
            case DOCUMENT_TYPE -> documentTypes.findById(id).map(documentType -> "Document type " + documentType.getCode());
            case FOLDER -> folders.findById(id).map(folder -> "Folder " + folder.getPathCache());
            default -> Optional.empty();
        };
    }
}
