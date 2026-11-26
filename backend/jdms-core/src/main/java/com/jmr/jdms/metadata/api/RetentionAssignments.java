package com.jmr.jdms.metadata.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which retention policy governs which document types ({@code document_type.retention_policy_id}). Written by the
 * records module when a retention policy change is approved.
 */
public interface RetentionAssignments {

    /** Assigns the policy to exactly these types (types that had it and are not listed lose it). */
    void assign(UUID retentionPolicyId, Set<UUID> documentTypeIds);

    /** The document types governed by the policy. */
    Set<UUID> typesOf(UUID retentionPolicyId);
}
