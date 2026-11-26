package com.jmr.jdms.platform.content;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters;
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming;
import com.google.crypto.tink.util.SecretBytes;
import com.jmr.jdms.platform.ids.TimeOrderedIds;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Envelope-encrypted blobs (ADR-0011, shared by core and worker since ADR-0014). Each object gets a fresh 256-bit data key, wrapped by the tenant key; the
 * content is encrypted with streaming AES-256-GCM (Tink AES-GCM-HKDF, 1 MiB segments), so every segment is
 * authenticated before it is released and truncation is detected. The blob key is the associated data, binding the
 * ciphertext to its location.
 *
 * <p>Blob keys: {@code <tenant_code>/<yyyy>/<mm>/<2 hex>/<uuid>}; the two hex characters come from a hash of the
 * UUID (spreads keys; known before the content is read). No file names or business data in keys.
 */
public class EnvelopeEncryption {

    public static final int SEGMENT_SIZE = 1024 * 1024;
    private static final int DATA_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BlobStore blobs;
    private final KeyService keys;
    private final Clock clock;
    private final AesGcmHkdfStreamingParameters parameters;

    public EnvelopeEncryption(BlobStore blobs, KeyService keys) {
        this.blobs = blobs;
        this.keys = keys;
        this.clock = Clock.systemUTC();
        try {
            this.parameters = AesGcmHkdfStreamingParameters.builder()
                    .setKeySizeBytes(DATA_KEY_BYTES)
                    .setDerivedAesGcmKeySizeBytes(32)
                    .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                    .setCiphertextSegmentSizeBytes(SEGMENT_SIZE)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts and stores the stream under a new blob key.
     *
     * @throws BlobTooLargeException beyond {@code maxBytes}; {@link EmptyBlobException} for an empty stream
     */
    public StoredBlob store(UUID tenantId, String tenantCode, InputStream plaintext, long maxBytes) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        RANDOM.nextBytes(dataKey);
        String blobKey = newBlobKey(tenantCode);
        MessageDigest sha256 = sha256();
        long size = 0;
        boolean complete = false;
        BlobStore.BlobWriter writer = null;
        try {
            // wrap first: a KMS failure must not leave a blob behind
            KeyService.WrappedKey wrapped = keys.wrap(tenantId, dataKey);
            writer = blobs.openWriter(blobKey);
            OutputStream encrypting = aead(dataKey).newEncryptingStream(writer, aad(blobKey));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = plaintext.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new BlobTooLargeException("Content exceeds " + maxBytes + " bytes");
                }
                sha256.update(buffer, 0, read);
                encrypting.write(buffer, 0, read);
            }
            if (size == 0) {
                throw new EmptyBlobException("Content is empty");
            }
            encrypting.close(); // writes the final segment and completes the object
            complete = true;
            return new StoredBlob(blobKey, HexFormat.of().formatHex(sha256.digest()), size, wrapped.wrapped(),
                    wrapped.keyRef());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store content", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt content", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            if (!complete && writer != null) {
                writer.abort();
            }
        }
    }

    /** A decrypting stream; each segment is authenticated before it is released (failures surface as IOException). */
    public InputStream open(UUID tenantId, StoredBlob content) {
        try {
            byte[] dataKey = keys.unwrap(tenantId, content.keyRef(), content.wrappedKey());
            try {
                return aead(dataKey).newDecryptingStream(blobs.read(content.blobKey()), aad(content.blobKey()));
            } finally {
                Arrays.fill(dataKey, (byte) 0);
            }
        } catch (BlobStore.BlobNotFoundException e) {
            throw new BlobUnreadableException("Blob not found", e);
        } catch (GeneralSecurityException | IOException | IllegalStateException e) {
            throw new BlobUnreadableException("Content cannot be decrypted", e);
        }
    }

    public void delete(String blobKey) {
        blobs.delete(blobKey);
    }

    private StreamingAead aead(byte[] dataKey) throws GeneralSecurityException {
        return AesGcmHkdfStreaming.create(AesGcmHkdfStreamingKey.create(parameters,
                SecretBytes.copyFrom(dataKey, InsecureSecretKeyAccess.get())));
    }

    /** {@code <tenant_code>/<yyyy>/<mm>/<2 hex>/<uuid>}. */
    public String newBlobKey(String tenantCode) {
        UUID id = TimeOrderedIds.next();
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
        String spread = HexFormat.of().formatHex(sha256().digest(id.toString().getBytes(StandardCharsets.US_ASCII)))
                .substring(0, 2);
        return "%s/%04d/%02d/%s/%s".formatted(tenantCode, now.getYear(), now.getMonthValue(), spread, id);
    }

    private static byte[] aad(String blobKey) {
        return blobKey.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
