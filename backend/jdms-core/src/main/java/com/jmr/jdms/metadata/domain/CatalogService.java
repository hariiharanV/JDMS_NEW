package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.error.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active metadata model of the caller's tenant, read by the UI for browsing and the upload form (B-08). It
 * describes configuration, not documents: which documents a caller may see is decided per document.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    public record CabinetEntry(UUID id, String code, String name) {
    }

    public record FolderEntry(UUID id, UUID parentId, String name, String path) {
    }

    public record Option(String code, String labelEn, String labelAr) {
    }

    /** A custom field as the upload form renders it; LOV fields carry their active options in display order. */
    public record FieldEntry(String key, String labelEn, String labelAr, FieldDataType dataType, boolean mandatory,
            Integer maxLength, String defaultValue, String lovCode, boolean searchable, boolean facet,
            boolean sensitive, int displayOrder, int displayRow, List<Option> options) {
    }

    public record TypeEntry(UUID id, UUID cabinetId, String code, String nameEn, String nameAr, String category,
            List<String> channels, Classification defaultClassification, List<String> requiredStandardFields,
            List<FieldEntry> fields) {
    }

    private final CabinetRepository cabinets;
    private final FolderRepository folders;
    private final DocumentTypeRepository documentTypes;
    private final FieldDefinitionRepository fields;
    private final LovRepository lovs;
    private final LovValueRepository lovValues;

    CatalogService(CabinetRepository cabinets, FolderRepository folders, DocumentTypeRepository documentTypes,
            FieldDefinitionRepository fields, LovRepository lovs, LovValueRepository lovValues) {
        this.cabinets = cabinets;
        this.folders = folders;
        this.documentTypes = documentTypes;
        this.fields = fields;
        this.lovs = lovs;
        this.lovValues = lovValues;
    }

    public List<CabinetEntry> cabinets() {
        return cabinets.findAll().stream().filter(cabinet -> cabinet.getStatus() == MetadataStatus.ACTIVE)
                .sorted(Comparator.comparing(Cabinet::getCode))
                .map(cabinet -> new CabinetEntry(cabinet.getId(), cabinet.getCode(), cabinet.getName())).toList();
    }

    /** The cabinet's whole folder tree, flat, parents before children. */
    public List<FolderEntry> folders(UUID cabinetId) {
        cabinets.findById(cabinetId).filter(cabinet -> cabinet.getStatus() == MetadataStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("CABINET_NOT_FOUND", "Cabinet not found."));
        return folders.findByCabinetId(cabinetId).stream()
                .sorted(Comparator.comparing(Folder::getPathCache))
                .map(folder -> new FolderEntry(folder.getId(), folder.getParentId(), folder.getName(),
                        folder.getPathCache())).toList();
    }

    /** Active document types, optionally of one cabinet, with their fields. */
    public List<TypeEntry> documentTypes(UUID cabinetId) {
        List<DocumentType> types = documentTypes.findAll().stream()
                .filter(type -> type.getStatus() == MetadataStatus.ACTIVE)
                .filter(type -> cabinetId == null || type.getCabinetId().equals(cabinetId))
                .sorted(Comparator.comparing(DocumentType::getCode)).toList();
        return types.stream().map(type -> {
            List<FieldSpec> specs = fields.findByDocumentTypeId(type.getId()).stream().map(FieldDefinition::spec)
                    .sorted(FieldSpec.DISPLAY_ORDER).toList();
            Map<String, List<Option>> options = optionsOf(specs);
            return new TypeEntry(type.getId(), type.getCabinetId(), type.getCode(), type.getNameEn(),
                    type.getNameAr(), type.getCategory(), type.getChannels(), type.getDefaultClassification(),
                    type.getRequiredStandardFields(), specs.stream().map(spec -> new FieldEntry(spec.key(),
                            spec.labelEn(), spec.labelAr(), spec.dataType(), spec.mandatory(), spec.maxLength(),
                            spec.defaultValue(), spec.lovCode(), spec.searchable(), spec.facet(), spec.sensitive(),
                            spec.displayOrder(), spec.displayRow(),
                            spec.lovCode() == null ? List.of() : options.getOrDefault(spec.lovCode(), List.of())))
                    .toList());
        }).toList();
    }

    private Map<String, List<Option>> optionsOf(List<FieldSpec> specs) {
        Set<String> codes = specs.stream().map(FieldSpec::lovCode).filter(code -> code != null)
                .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codeById = lovs.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(Lov::getId, Lov::getCode));
        return lovValues.findByLovIdIn(codeById.keySet()).stream().filter(LovValue::isActive)
                .sorted(Comparator.comparingInt(LovValue::getDisplayOrder).thenComparing(LovValue::getCode))
                .collect(Collectors.groupingBy(value -> codeById.get(value.getLovId()),
                        Collectors.mapping(value -> new Option(value.getCode(), value.getLabelEn(),
                                value.getLabelAr()), Collectors.toList())));
    }
}
