package com.jmr.jdms.document.infra;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage over the S3 API (D-12). Credentials come from the environment only.
 *
 * @param createBucket create the bucket at startup if missing (local development and tests only; production buckets
 *                     are provisioned with object lock)
 */
@ConfigurationProperties("jdms.storage")
@Validated
public record StorageProperties(
        @NotBlank String endpoint,
        @NotBlank @DefaultValue("us-east-1") String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @DefaultValue("true") boolean pathStyleAccess,
        @DefaultValue("false") boolean createBucket,
        @DefaultValue("PT60S") Duration timeout,
        @DefaultValue Reconcile reconcile) {

    /**
     * Orphan blob detection: blobs older than {@code grace} that no version references.
     *
     * @param deleteOrphans delete them instead of only reporting
     */
    public record Reconcile(@DefaultValue("PT1H") Duration grace, @DefaultValue("false") boolean deleteOrphans) {
    }
}
