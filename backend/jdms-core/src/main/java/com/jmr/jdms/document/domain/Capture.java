package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A file attached on the upload screen and read by the worker to suggest field values before the document exists
 * (ADR-0024). Belongs to the user who attached it. The file's blob is deleted once read; the suggestions are an
 * encrypted blob; the whole capture expires.
 */
@Entity
@Table(name = "capture")
public class Capture extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_type_id", nullable = false, updatable = false)
    private UUID documentTypeId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "folder_id", updatable = false)
    private UUID folderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Classification classification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaptureStatus status;

    @Column(name = "blob_key", length = 300)
    private String blobKey;

    @Column(length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "encrypted_data_key", length = 500)
    private String encryptedDataKey;

    @Column(name = "key_ref", length = 200)
    private String keyRef;

    @Column(name = "detected_mime_type", length = 150)
    private String detectedMimeType;

    @Column(nullable = false)
    private boolean ocr;

    @Column(name = "suggestion_count", nullable = false)
    private int suggestionCount;

    @Column(name = "sugg_blob_key", length = 300)
    private String suggestionsBlobKey;

    @Column(name = "sugg_sha256", length = 64)
    private String suggestionsSha256;

    @Column(name = "sugg_size_bytes")
    private Long suggestionsSizeBytes;

    @Column(name = "sugg_data_key", length = 500)
    private String suggestionsDataKey;

    @Column(name = "sugg_key_ref", length = 200)
    private String suggestionsKeyRef;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected Capture() {
    }

    public Capture(UUID documentTypeId, UUID branchId, UUID folderId, Classification classification,
            StoredContent content, Instant expiresAt) {
        this.id = Ids.newId();
        this.documentTypeId = documentTypeId;
        this.branchId = branchId;
        this.folderId = folderId;
        this.classification = classification;
        this.status = CaptureStatus.PENDING;
        this.blobKey = content.blobKey();
        this.sha256 = content.sha256();
        this.sizeBytes = content.sizeBytes();
        this.encryptedDataKey = content.wrappedKey();
        this.keyRef = content.keyRef();
        this.expiresAt = expiresAt;
    }

    /** The worker's reading is complete: the file is no longer needed (its blob is deleted by the caller). */
    void finish(CaptureStatus outcome, String detectedMimeType, boolean ocr, StoredContent suggestions, int count) {
        this.status = outcome;
        this.detectedMimeType = detectedMimeType;
        this.ocr = ocr;
        this.suggestionCount = suggestions == null ? 0 : count;
        if (suggestions != null) {
            this.suggestionsBlobKey = suggestions.blobKey();
            this.suggestionsSha256 = suggestions.sha256();
            this.suggestionsSizeBytes = suggestions.sizeBytes();
            this.suggestionsDataKey = suggestions.wrappedKey();
            this.suggestionsKeyRef = suggestions.keyRef();
        }
        forgetContent();
    }

    void forgetContent() {
        this.blobKey = null;
        this.sha256 = null;
        this.encryptedDataKey = null;
        this.keyRef = null;
    }

    /** The attached file, while the worker has not read it yet. */
    public StoredContent content() {
        return blobKey == null ? null : new StoredContent(blobKey, sha256, sizeBytes, encryptedDataKey, keyRef);
    }

    public StoredContent suggestions() {
        return suggestionsBlobKey == null ? null : new StoredContent(suggestionsBlobKey, suggestionsSha256,
                suggestionsSizeBytes, suggestionsDataKey, suggestionsKeyRef);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public Classification getClassification() {
        return classification;
    }

    public CaptureStatus getStatus() {
        return status;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getDetectedMimeType() {
        return detectedMimeType;
    }

    public boolean isOcr() {
        return ocr;
    }

    public int getSuggestionCount() {
        return suggestionCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
