package com.jmr.jdms.document.infra;

import com.jmr.jdms.platform.content.BlobStore;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.KeyService;
import com.jmr.jdms.platform.content.LocalKeyService;
import com.jmr.jdms.platform.content.S3BlobStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Blob storage and envelope encryption from the shared platform module (ADR-0014). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, KmsProperties.class})
class StorageConfiguration {

    @Bean
    S3BlobStore blobStore(StorageProperties properties) {
        return new S3BlobStore(new S3BlobStore.Settings(properties.endpoint(), properties.region(),
                properties.bucket(), properties.accessKey(), properties.secretKey(), properties.pathStyleAccess(),
                properties.createBucket(), properties.timeout()));
    }

    @Bean
    KeyService keyService(KmsProperties properties) {
        return new LocalKeyService(properties.localMasterKey(), properties.generateEphemeralKey());
    }

    @Bean
    EnvelopeEncryption envelopeEncryption(BlobStore blobs, KeyService keys) {
        return new EnvelopeEncryption(blobs, keys);
    }
}
