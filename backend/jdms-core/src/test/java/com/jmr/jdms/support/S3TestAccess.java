package com.jmr.jdms.support;

import com.jmr.jdms.document.infra.StorageProperties;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Raw access to the test bucket, to inspect and tamper with stored ciphertext. */
@Component
public class S3TestAccess implements DisposableBean {

    private final S3Client s3;
    private final String bucket;

    public S3TestAccess(StorageProperties properties) {
        this.bucket = properties.bucket();
        this.s3 = S3Client.builder().endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKey(), properties.secretKey())))
                .build();
    }

    public List<String> keys(String prefix) {
        return s3.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3Object::key).toList();
    }

    public byte[] read(String key) {
        return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
    }

    public void write(String key, byte[] bytes) {
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }

    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(bucket).key(key));
    }

    @Override
    public void destroy() {
        s3.close();
    }
}
