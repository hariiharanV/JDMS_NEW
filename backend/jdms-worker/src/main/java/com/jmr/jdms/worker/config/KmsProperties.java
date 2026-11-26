package com.jmr.jdms.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The local KMS stub's master key, the same as the core's ({@code JDMS_LOCAL_KMS_MASTER_KEY}). */
@ConfigurationProperties("jdms.kms")
public record KmsProperties(String localMasterKey, @DefaultValue("false") boolean generateEphemeralKey) {
}
