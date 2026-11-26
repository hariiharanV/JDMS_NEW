package com.jmr.jdms.platform.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Spec §4.8 / B-10: a declared record's blob under compliance-mode object lock cannot be deleted, not even by the
 * storage administrator. Needs the production candidate store (O-01) with an object-lock bucket, so it is excluded
 * from the default build; run with {@code -Dtest.excludedGroups=none} and {@code JDMS_WORM_S3_ENDPOINT},
 * {@code JDMS_WORM_S3_BUCKET}, {@code JDMS_WORM_S3_ACCESS_KEY}, {@code JDMS_WORM_S3_SECRET_KEY} (admin credentials).
 */
@Tag("requires-worm-store")
class WormStoreIT {

    @Test
    void aLockedVersionCannotBeDeletedEvenByTheStorageAdministrator() {
        String bucket = System.getenv("JDMS_WORM_S3_BUCKET");
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(System.getenv("JDMS_WORM_S3_ENDPOINT")))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("JDMS_WORM_S3_ACCESS_KEY"), System.getenv("JDMS_WORM_S3_SECRET_KEY"))))
                .build()) {
            String key = "worm-test/" + UUID.randomUUID();
            String version = s3.putObject(request -> request.bucket(bucket).key(key)
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(Instant.now().plus(1, ChronoUnit.DAYS)),
                    RequestBody.fromString("record")).versionId();

            assertThatThrownBy(() -> s3.deleteObject(request -> request.bucket(bucket).key(key).versionId(version)
                    .bypassGovernanceRetention(true))).isInstanceOf(S3Exception.class);
        }
    }
}
