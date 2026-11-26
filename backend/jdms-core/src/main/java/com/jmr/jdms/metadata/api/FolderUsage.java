package com.jmr.jdms.metadata.api;

import java.util.UUID;

/**
 * Implemented by modules whose data refers to folders (documents), so the metadata module can refuse to delete a
 * folder in use without depending on them.
 */
public interface FolderUsage {

    boolean isFolderInUse(UUID folderId);
}
