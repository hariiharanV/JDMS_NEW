package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A derived representation of a version (spec §4.2): PDF for preview, PNG thumbnail, extracted or OCR text. Each is
 * its own envelope-encrypted blob produced by the worker; the original is never modified. Never changed.
 */
@Entity
@Table(name = "rendition")
public class Rendition extends TenantScopedEntity {

    public static final String PDF = "PDF";
    public static final String THUMBNAIL = "THUMBNAIL";
    public static final String TEXT = "TEXT";

    @Id
    private UUID id;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(nullable = false, updatable = false, length = 20)
    private String kind;

    @Column(name = "mime_type", nullable = false, updatable = false, length = 100)
    private String mimeType;

    @Column(name = "blob_key", nullable = false, updatable = false, length = 300)
    private String blobKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "encrypted_data_key", nullable = false, updatable = false, length = 500)
    private String encryptedDataKey;

    @Column(name = "key_ref", nullable = false, updatable = false, length = 200)
    private String keyRef;

    @Column(nullable = false, updatable = false)
    private boolean ocr;

    protected Rendition() {
    }

    public Rendition(UUID versionId, String kind, String mimeType, StoredContent content, boolean ocr) {
        this.id = Ids.newId();
        this.versionId = versionId;
        this.kind = kind;
        this.mimeType = mimeType;
        this.blobKey = content.blobKey();
        this.sha256 = content.sha256();
        this.sizeBytes = content.sizeBytes();
        this.encryptedDataKey = content.wrappedKey();
        this.keyRef = content.keyRef();
        this.ocr = ocr;
    }

    public StoredContent content() {
        return new StoredContent(blobKey, sha256, sizeBytes, encryptedDataKey, keyRef);
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getKind() {
        return kind;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getBlobKey() {
        return blobKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isOcr() {
        return ocr;
    }
}
