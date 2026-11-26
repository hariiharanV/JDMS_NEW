package com.jmr.jdms.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EnvelopeEncryptionTest {

    private final InMemoryBlobStore blobs = new InMemoryBlobStore();
    private final LocalKeyService keys = new LocalKeyService(randomKey(), false);
    private final EnvelopeEncryption store = new EnvelopeEncryption(blobs, keys);
    private final UUID tenant = UUID.randomUUID();

    @Test
    void roundTripAcrossSegmentsWithHashAndSize() throws Exception {
        byte[] content = random(3 * EnvelopeEncryption.SEGMENT_SIZE + 17);

        StoredBlob stored = store.store(tenant, "BANK1", new ByteArrayInputStream(content), Long.MAX_VALUE);

        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content)));
        assertThat(stored.blobKey()).startsWith("BANK1/");
        assertThat(blobs.objects.get(stored.blobKey())).isNotEqualTo(content);
        try (InputStream in = store.open(tenant, stored)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void anotherTenantCannotUnwrapTheDataKey() {
        StoredBlob stored = store.store(tenant, "BANK1", new ByteArrayInputStream(random(100)), Long.MAX_VALUE);

        assertThatThrownBy(() -> store.open(UUID.randomUUID(), stored)).isInstanceOf(BlobUnreadableException.class);
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        StoredBlob stored = store.store(tenant, "BANK1", new ByteArrayInputStream(random(5000)), Long.MAX_VALUE);
        blobs.objects.get(stored.blobKey())[100] ^= 0x01;

        assertThatThrownBy(() -> {
            try (InputStream in = store.open(tenant, stored)) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    void truncatedCiphertextIsDetected() {
        StoredBlob stored = store.store(tenant, "BANK1",
                new ByteArrayInputStream(random(2 * EnvelopeEncryption.SEGMENT_SIZE)), Long.MAX_VALUE);
        byte[] ciphertext = blobs.objects.get(stored.blobKey());
        blobs.objects.put(stored.blobKey(), java.util.Arrays.copyOf(ciphertext, EnvelopeEncryption.SEGMENT_SIZE));

        assertThatThrownBy(() -> {
            try (InputStream in = store.open(tenant, stored)) {
                in.readAllBytes();
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    void tooLargeAndEmptyContentLeaveNothingBehind() {
        assertThatThrownBy(() -> store.store(tenant, "BANK1", new ByteArrayInputStream(random(101)), 100))
                .isInstanceOf(BlobTooLargeException.class);
        assertThatThrownBy(() -> store.store(tenant, "BANK1", new ByteArrayInputStream(new byte[0]), 100))
                .isInstanceOf(EmptyBlobException.class);
        assertThat(blobs.objects).isEmpty();
        assertThat(blobs.aborted).isEqualTo(2);
    }

    @Test
    void localKmsRequiresAMasterKey() {
        assertThatThrownBy(() -> new LocalKeyService(null, false))
                .hasMessageContaining("JDMS_LOCAL_KMS_MASTER_KEY");
        assertThatThrownBy(() -> new LocalKeyService(Base64.getEncoder().encodeToString(new byte[16]), false))
                .hasMessageContaining("32 bytes");
    }

    private static String randomKey() {
        return Base64.getEncoder().encodeToString(random(32));
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static final class InMemoryBlobStore implements BlobStore {

        final Map<String, byte[]> objects = new HashMap<>();
        int aborted;

        @Override
        public BlobWriter openWriter(String key) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            return new BlobWriter() {
                private boolean done;

                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    buffer.write(bytes, offset, length);
                }

                @Override
                public void close() {
                    if (!done) {
                        done = true;
                        objects.put(key, buffer.toByteArray());
                    }
                }

                @Override
                public void abort() {
                    if (!done) {
                        done = true;
                        aborted++;
                    }
                }
            };
        }

        @Override
        public InputStream read(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new BlobNotFoundException(key);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public void forEach(Consumer<BlobInfo> consumer) {
            objects.keySet().forEach(key -> consumer.accept(new BlobInfo(key, java.time.Instant.EPOCH)));
        }
    }
}
