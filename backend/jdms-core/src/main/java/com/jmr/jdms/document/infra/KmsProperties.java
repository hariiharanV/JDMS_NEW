package com.jmr.jdms.document.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Key management. The PoC has only the local KMS stub (CLAUDE.md rule 7): a master key from the environment, from
 * which a key per tenant is derived. A real KMS replaces it behind {@code KeyService}.
 *
 * @param localMasterKey        base64 of 32 random bytes ({@code JDMS_LOCAL_KMS_MASTER_KEY}); never in files
 * @param generateEphemeralKey  tests only: generate a random master key at startup (data is unreadable afterwards)
 */
@ConfigurationProperties("jdms.kms")
public record KmsProperties(String localMasterKey, @DefaultValue("false") boolean generateEphemeralKey) {
}
