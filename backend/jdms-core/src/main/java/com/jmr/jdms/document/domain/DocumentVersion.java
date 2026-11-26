package com.jmr.jdms.document.domain;

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
 * An immutable version of a document's content (spec §4.3). Content columns are written once ({@code updatable =
 * false}) and a database trigger rejects any change to them; only the status columns move. The blob key is internal
 * and never derived from file names (CLAUDE.md rule 5).
 */
@Entity
@Table(name = "document_version")
public class DocumentVersion extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private int major;

    @Column(nullable = false, updatable = false)
    private int minor;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus;

    @Column(name = "blob_key", nullable = false, updatable = false, length = 300)
    private String blobKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "mime_type", nullable = false, updatable = false, length = 150)
    private String mimeType;

    /** Display only: never used to locate content. */
    @Column(name = "original_filename", updatable = false, length = 255)
    private String originalFilename;

    @Column(name = "page_count")
    private Integer pageCount;

    /** Detected by the worker from the content; {@code mimeType} is what the client claimed. */
    @Column(name = "detected_mime_type", length = 150)
    private String detectedMimeType;

    @Column(name = "version_comment", updatable = false, length = 1000)
    private String versionComment;

    @Column(name = "legacy_created_at", updatable = false)
    private Instant legacyCreatedAt;

    @Column(name = "legacy_created_by", length = 100, updatable = false)
    private String legacyCreatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_status", nullable = false, length = 20)
    private StorageStatus storageStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 20)
    private IndexStatus indexStatus;

    /** The object's data key, wrapped by the tenant key (envelope encryption). */
    @Column(name = "encrypted_data_key", nullable = false, updatable = false, length = 500)
    private String encryptedDataKey;

    @Column(name = "key_ref", nullable = false, updatable = false, length = 200)
    private String keyRef;

    protected DocumentVersion() {
    }

    public DocumentVersion(UUID id, UUID documentId, int major, int minor, ApprovalStatus approvalStatus,
            StoredContent content, String mimeType, String originalFilename, String versionComment) {
        this.id = id == null ? Ids.newId() : id;
        this.documentId = documentId;
        this.major = major;
        this.minor = minor;
        this.approvalStatus = approvalStatus;
        this.blobKey = content.blobKey();
        this.sha256 = content.sha256();
        this.sizeBytes = content.sizeBytes();
        this.encryptedDataKey = content.wrappedKey();
        this.keyRef = content.keyRef();
        this.mimeType = mimeType;
        this.originalFilename = originalFilename;
        this.versionComment = versionComment;
        this.storageStatus = StorageStatus.STORED;
        this.processingStatus = ProcessingStatus.PENDING;
        this.indexStatus = IndexStatus.PENDING;
    }

    public void approve() {
        this.approvalStatus = ApprovalStatus.APPROVED;
    }

    public void reject() {
        this.approvalStatus = ApprovalStatus.REJECTED;
    }

    public void markMissing() {
        this.storageStatus = StorageStatus.MISSING;
    }

    void markDestroyed() {
        this.storageStatus = StorageStatus.DESTROYED;
    }

    /** Processing results (B-06). A quarantined version stays quarantined whatever arrives later. */
    void processingStarted() {
        if (processingStatus == ProcessingStatus.PENDING) {
            processingStatus = ProcessingStatus.PROCESSING;
        }
    }

    void quarantine() {
        processingStatus = ProcessingStatus.QUARANTINED;
    }

    void processingFinished(boolean failed) {
        if (processingStatus != ProcessingStatus.QUARANTINED) {
            processingStatus = failed ? ProcessingStatus.FAILED : ProcessingStatus.DONE;
        }
    }

    void describeContent(String detectedMimeType, Integer pageCount) {
        if (detectedMimeType != null) {
            this.detectedMimeType = detectedMimeType;
        }
        if (pageCount != null) {
            this.pageCount = pageCount;
        }
    }

    /** The legacy JDMS time and user of an imported version (ADR-0020); set before the first save. */
    void importedFrom(Instant createdAt, String createdBy) {
        this.legacyCreatedAt = createdAt;
        this.legacyCreatedBy = createdBy;
    }

    void markIndexed() {
        this.indexStatus = IndexStatus.INDEXED;
    }

    public String getDetectedMimeType() {
        return detectedMimeType;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public String number() {
        return major + "." + minor;
    }

    public StoredContent content() {
        return new StoredContent(blobKey, sha256, sizeBytes, encryptedDataKey, keyRef);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public String getBlobKey() {
        return blobKey;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getVersionComment() {
        return versionComment;
    }

    public StorageStatus getStorageStatus() {
        return storageStatus;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public IndexStatus getIndexStatus() {
        return indexStatus;
    }
}
