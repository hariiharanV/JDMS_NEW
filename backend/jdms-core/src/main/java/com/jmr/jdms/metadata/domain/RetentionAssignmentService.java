package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.RetentionAssignments;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link RetentionAssignments} on document types (current tenant). */
@Service
class RetentionAssignmentService implements RetentionAssignments {

    private final DocumentTypeRepository types;

    RetentionAssignmentService(DocumentTypeRepository types) {
        this.types = types;
    }

    @Override
    @Transactional
    public void assign(UUID retentionPolicyId, Set<UUID> documentTypeIds) {
        types.findByRetentionPolicyId(retentionPolicyId).stream()
                .filter(type -> !documentTypeIds.contains(type.getId()))
                .forEach(type -> type.assignRetentionPolicy(null));
        types.findAllById(documentTypeIds).forEach(type -> type.assignRetentionPolicy(retentionPolicyId));
        types.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> typesOf(UUID retentionPolicyId) {
        return types.findByRetentionPolicyId(retentionPolicyId).stream().map(DocumentType::getId)
                .collect(Collectors.toSet());
    }
}
