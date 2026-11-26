package com.jmr.jdms.contracts;

import java.util.UUID;

/** Antivirus found malware in a version; {@code uploaderId} is notified. */
public record VersionQuarantined(UUID tenantId, UUID documentId, UUID versionId, UUID uploaderId,
        String signature) {
}
