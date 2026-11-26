package com.jmr.jdms.perf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Generator settings ({@code jdms.perf.*} in {@code perf.yml}).
 *
 * @param documents   total synthetic documents wanted in the tenant; a re-run continues from the current count
 * @param concurrency concurrent blob uploads
 * @param arabicShare share of documents written in Arabic (0 to 1)
 * @param customers   distinct synthetic customers the documents belong to
 * @param seed        random seed: the same seed and index always give the same document
 */
@ConfigurationProperties("jdms.perf")
public record PerfProperties(String tenantCode, long documents, int batchSize, int concurrency, double arabicShare,
        int customers, long seed) {
}
