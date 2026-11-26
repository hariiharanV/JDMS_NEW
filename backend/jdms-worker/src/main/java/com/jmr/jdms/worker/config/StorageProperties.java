package com.jmr.jdms.worker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Object storage, as configured for the core ({@code jdms.storage.*}); credentials from the environment only. */
@ConfigurationProperties("jdms.storage")
public record StorageProperties(String endpoint, @DefaultValue("us-east-1") String region, String bucket,
        String accessKey, String secretKey, @DefaultValue("true") boolean pathStyleAccess,
        @DefaultValue("false") boolean createBucket, @DefaultValue("PT60S") Duration timeout) {
}
