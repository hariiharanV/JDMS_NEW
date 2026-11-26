package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.DocumentTypeInfo;
import com.jmr.jdms.metadata.api.FieldInfo;
import com.jmr.jdms.metadata.api.MetadataCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class MetadataCatalogService implements MetadataCatalog {

    private final DocumentTypeRepository documentTypes;
    private final CabinetRepository cabinets;
    private final FolderRepository folders;
    private final FieldDefinitionRepository fields;

    MetadataCatalogService(DocumentTypeRepository documentTypes, CabinetRepository cabinets, FolderRepository folders,
            FieldDefinitionRepository fields) {
        this.documentTypes = documentTypes;
        this.cabinets = cabinets;
        this.folders = folders;
        this.fields = fields;
    }

    @Override
    public Optional<DocumentTypeInfo> findDocumentType(String code) {
        return documentTypes.findByCode(code).map(this::info);
    }

    @Override
    public Optional<DocumentTypeInfo> findDocumentType(UUID id) {
        return documentTypes.findById(id).map(this::info);
    }

    @Override
    public Optional<String> cabinetCode(UUID cabinetId) {
        return cabinets.findById(cabinetId).map(Cabinet::getCode);
    }

    @Override
    public boolean folderInCabinet(UUID folderId, UUID cabinetId) {
        return folders.findById(folderId).map(folder -> folder.getCabinetId().equals(cabinetId)).orElse(false);
    }

    @Override
    public Optional<UUID> folderByPath(UUID cabinetId, String path) {
        String[] names = java.util.Arrays.stream(path.split("/")).map(String::strip).filter(name -> !name.isEmpty())
                .toArray(String[]::new);
        if (names.length == 0 || names.length > FolderAdministration.MAX_DEPTH + 1) {
            return Optional.empty();
        }
        Optional<Folder> current = folders.findByCabinetIdAndParentIdIsNullAndName(cabinetId, names[0]);
        for (int i = 1; i < names.length && current.isPresent(); i++) {
            current = folders.findByCabinetIdAndParentIdAndName(cabinetId, current.get().getId(), names[i]);
        }
        return current.map(Folder::getId);
    }

    @Override
    public List<UUID> folderPath(UUID folderId) {
        List<UUID> path = new ArrayList<>();
        UUID current = folderId;
        while (current != null && path.size() <= FolderAdministration.MAX_DEPTH) {
            Folder folder = folders.findById(current).orElse(null);
            if (folder == null) {
                break;
            }
            path.add(folder.getId());
            current = folder.getParentId();
        }
        return path;
    }

    @Override
    public Set<UUID> folderSubtree(UUID folderId) {
        Folder root = folders.findById(folderId).orElse(null);
        if (root == null) {
            return Set.of();
        }
        List<Folder> all = folders.findByCabinetId(root.getCabinetId());
        Set<UUID> subtree = new HashSet<>(Set.of(root.getId()));
        boolean grew = true;
        while (grew) {
            grew = all.stream().filter(folder -> folder.getParentId() != null && subtree.contains(folder.getParentId()))
                    .map(Folder::getId).filter(subtree::add).count() > 0;
        }
        return subtree;
    }

    private DocumentTypeInfo info(DocumentType type) {
        Cabinet cabinet = cabinets.findById(type.getCabinetId()).orElseThrow();
        Set<String> sensitive = fields.findByDocumentTypeId(type.getId()).stream().map(FieldDefinition::spec)
                .filter(FieldSpec::sensitive).map(FieldSpec::key).collect(Collectors.toSet());
        return new DocumentTypeInfo(type.getId(), type.getCode(), type.getCategory(),
                type.getStatus() == MetadataStatus.ACTIVE,
                cabinet.getId(), cabinet.getCode(), cabinet.getStatus() == MetadataStatus.ACTIVE,
                cabinet.getMaxFileSizeMb(), cabinet.getQuotaMb(), new HashSet<>(type.getChannels()),
                type.getDefaultClassification(), type.getProcessKey(), type.isMetaChangeNeedsApproval(),
                type.isDeleteRequiresApproval(), type.isDeclareRecordOnApproval(),
                new HashSet<>(type.getRequiredStandardFields()), sensitive, type.getRetentionPolicyId());
    }

    @Override
    public List<FieldInfo> fields(UUID documentTypeId) {
        return fields.findByDocumentTypeId(documentTypeId).stream().map(MetadataCatalogService::fieldInfo).toList();
    }

    @Override
    public List<FieldInfo> allFields() {
        return fields.findAll().stream().map(MetadataCatalogService::fieldInfo).toList();
    }

    private static FieldInfo fieldInfo(FieldDefinition field) {
        FieldSpec spec = field.spec();
        return new FieldInfo(field.getDocumentTypeId(), spec.key(), spec.dataType().name(), spec.searchable(),
                spec.facet(), spec.sensitive());
    }
}
