package com.jmr.jdms.perf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The object store the core uses ({@code jdms.storage.*}); credentials from the environment. */
@ConfigurationProperties("jdms.storage")
public record StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey) {
}
