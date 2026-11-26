package com.jmr.jdms.perf.config;

import com.jmr.jdms.platform.content.BlobStore;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.KeyService;
import com.jmr.jdms.platform.content.LocalKeyService;
import com.jmr.jdms.platform.content.S3BlobStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Content is written exactly as the core writes it: envelope encryption with the tenant key (ADR-0011). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PerfProperties.class, StorageProperties.class})
class PerfConfiguration {

    @Bean(destroyMethod = "close")
    S3BlobStore blobStore(StorageProperties storage) {
        return new S3BlobStore(new S3BlobStore.Settings(storage.endpoint(), storage.region(), storage.bucket(),
                storage.accessKey(), storage.secretKey(), true, false, Duration.ofSeconds(30)));
    }

    @Bean
    KeyService keyService(@Value("${jdms.kms.local-master-key:}") String masterKey) {
        return new LocalKeyService(masterKey, false);
    }

    @Bean
    EnvelopeEncryption envelopeEncryption(BlobStore blobs, KeyService keys) {
        return new EnvelopeEncryption(blobs, keys);
    }
}
