package com.jmr.jdms.document.infra;

import com.jmr.jdms.document.domain.ContentStore;
import com.jmr.jdms.document.domain.ContentTooLargeException;
import com.jmr.jdms.document.domain.ContentUnavailableException;
import com.jmr.jdms.document.domain.EmptyContentException;
import com.jmr.jdms.document.domain.StoredContent;
import com.jmr.jdms.platform.content.BlobStoreUnavailableException;
import com.jmr.jdms.platform.content.BlobTooLargeException;
import com.jmr.jdms.platform.content.BlobUnreadableException;
import com.jmr.jdms.platform.content.EmptyBlobException;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.shared.error.ApiException;
import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** {@link ContentStore} over the shared envelope encryption (ADR-0011, ADR-0014), in the document domain's terms. */
@Component
class EncryptedContentStore implements ContentStore {

    static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(EncryptedContentStore.class);

    private final EnvelopeEncryption encryption;

    EncryptedContentStore(EnvelopeEncryption encryption) {
        this.encryption = encryption;
    }

    @Override
    public StoredContent store(UUID tenantId, String tenantCode, InputStream plaintext, long maxBytes) {
        try {
            StoredBlob blob = encryption.store(tenantId, tenantCode, plaintext, maxBytes);
            return new StoredContent(blob.blobKey(), blob.sha256(), blob.sizeBytes(), blob.wrappedKey(),
                    blob.keyRef());
        } catch (BlobTooLargeException e) {
            throw new ContentTooLargeException(e.getMessage());
        } catch (EmptyBlobException e) {
            throw new EmptyContentException(e.getMessage());
        } catch (BlobStoreUnavailableException e) {
            throw storageUnavailable(e);
        }
    }

    @Override
    public InputStream open(UUID tenantId, StoredContent content) {
        try {
            return encryption.open(tenantId, new StoredBlob(content.blobKey(), content.sha256(), content.sizeBytes(),
                    content.wrappedKey(), content.keyRef()));
        } catch (BlobUnreadableException e) {
            throw new ContentUnavailableException(e.getMessage(), e);
        } catch (BlobStoreUnavailableException e) {
            throw storageUnavailable(e);
        }
    }

    /** 503: the request may be retried (an upload with the same Idempotency-Key is safe to repeat). */
    private static ApiException storageUnavailable(BlobStoreUnavailableException e) {
        log.warn("Object store unavailable: {}", e.getCause() == null ? e.getMessage() : e.getCause().toString());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE,
                "File storage is temporarily unavailable; retry later.");
    }

    @Override
    public void delete(String blobKey) {
        encryption.delete(blobKey);
    }
}
