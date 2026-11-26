package com.jmr.jdms.document.infra;

import com.jmr.jdms.document.domain.CaptureService;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assisted indexing settings ({@code jdms.capture.*}, ADR-0024). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CaptureConfiguration.CaptureProperties.class)
class CaptureConfiguration {

    /**
     * @param ttl        how long a capture and its suggestions are kept
     * @param maxPending captures one user may have being read at once
     * @param expiryCron when expired captures are removed
     */
    @ConfigurationProperties("jdms.capture")
    record CaptureProperties(@DefaultValue("PT1H") Duration ttl, @DefaultValue("5") int maxPending,
            @DefaultValue("0 */10 * * * *") String expiryCron) {
    }

    @Bean
    CaptureService.Settings captureSettings(CaptureProperties properties) {
        return new CaptureService.Settings(properties.ttl(), properties.maxPending());
    }
}
