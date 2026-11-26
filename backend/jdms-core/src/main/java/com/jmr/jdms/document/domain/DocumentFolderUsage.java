package com.jmr.jdms.document.domain;

import com.jmr.jdms.metadata.api.FolderUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the metadata module whether documents are filed in a folder (folders in use cannot be deleted). */
@Component
class DocumentFolderUsage implements FolderUsage {

    private final DocumentRepository documents;

    DocumentFolderUsage(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFolderInUse(UUID folderId) {
        return documents.existsByFolderId(folderId);
    }
}
