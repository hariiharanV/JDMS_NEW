package com.jmr.jdms.platform.content;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link BlobStore} over the S3 API (AWS SDK v2). Writes stream: objects up to one part are sent with a single PUT,
 * larger ones as a multipart upload of {@value #PART_SIZE}-byte parts, so content length need not be known upfront
 * and at most one part is held in memory.
 */
public class S3BlobStore implements BlobStore, AutoCloseable {

    static final int PART_SIZE = 8 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);

    private final S3Client s3;
    private final String bucket;

    /**
     * Connection settings; credentials come from the environment only.
     *
     * @param createBucket create the bucket if missing (local development and tests only; production buckets are
     *                     provisioned with object lock)
     */
    public record Settings(String endpoint, String region, String bucket, String accessKey, String secretKey,
            boolean pathStyleAccess, boolean createBucket, Duration timeout) {
    }

    public S3BlobStore(Settings settings) {
        this.bucket = settings.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(settings.timeout()))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(settings.timeout()))
                .build();
        if (settings.createBucket()) {
            createBucketIfMissing();
        }
    }

    @Override
    public BlobWriter openWriter(String key) {
        return new MultipartWriter(key);
    }

    @Override
    public InputStream read(String key) {
        try {
            return s3.getObject(request -> request.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new BlobNotFoundException(key);
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw unavailable(e);
        }
    }

    /** Client-side failures (connection, timeout) and server errors; never 4xx answers about the request itself. */
    static RuntimeException unavailable(SdkException e) {
        if (e instanceof S3Exception s3 && s3.statusCode() < 500) {
            return e;
        }
        return new BlobStoreUnavailableException("Object store unavailable", e);
    }

    @Override
    public void forEach(Consumer<BlobInfo> consumer) {
        s3.listObjectsV2Paginator(request -> request.bucket(bucket)).contents()
                .forEach(object -> consumer.accept(new BlobInfo(object.key(), object.lastModified())));
    }

    @Override
    public void close() {
        s3.close();
    }

    private void createBucketIfMissing() {
        try {
            s3.headBucket(request -> request.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(request -> request.bucket(bucket));
            log.info("Created bucket {}", bucket);
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(request -> request.bucket(bucket));
            log.info("Created bucket {}", bucket);
        }
    }

    /** Buffers one part; switches to a multipart upload when the content outgrows it. */
    private final class MultipartWriter extends BlobWriter {

        private final String key;
        private final List<CompletedPart> parts = new ArrayList<>();
        private ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        private String uploadId;
        private boolean finished;

        MultipartWriter(String key) {
            this.key = key;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            buffer.write(bytes, offset, length);
            if (buffer.size() >= PART_SIZE) {
                flushPart();
            }
        }

        private void flushPart() {
            try {
                uploadPart();
            } catch (SdkException e) {
                throw unavailable(e);
            }
        }

        private void uploadPart() {
            if (uploadId == null) {
                uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key(key)).uploadId();
            }
            int partNumber = parts.size() + 1;
            byte[] part = buffer.toByteArray();
            buffer = new ByteArrayOutputStream(64 * 1024);
            String etag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                    .partNumber(partNumber), RequestBody.fromBytes(part)).eTag();
            parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
        }

        @Override
        public void close() {
            if (finished) {
                return;
            }
            try {
                if (uploadId == null) {
                    byte[] whole = buffer.toByteArray();
                    s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromBytes(whole));
                } else {
                    if (buffer.size() > 0) {
                        flushPart();
                    }
                    s3.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                            .multipartUpload(upload -> upload.parts(parts)));
                }
                finished = true;
            } catch (SdkException e) {
                abort();
                throw unavailable(e);
            } catch (RuntimeException e) {
                abort();
                throw e;
            }
        }

        @Override
        public void abort() {
            if (finished) {
                return;
            }
            finished = true;
            buffer = new ByteArrayOutputStream(0);
            if (uploadId != null) {
                try {
                    s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId));
                } catch (RuntimeException e) {
                    log.warn("Could not abort multipart upload; the reconciliation job will remove it");
                }
            }
        }
    }
}
