package com.jmr.jdms.contracts;

import java.util.UUID;

/** A new version (including a document's first version and drafts) was committed and needs processing. */
public record VersionCreated(UUID tenantId, UUID documentId, UUID versionId) {
}
