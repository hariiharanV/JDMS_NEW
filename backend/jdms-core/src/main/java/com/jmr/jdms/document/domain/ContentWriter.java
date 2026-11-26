package com.jmr.jdms.document.domain;

import com.jmr.jdms.shared.context.RequestContext;
import com.jmr.jdms.shared.error.ApiException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Stores uploaded content within a size limit, mapping storage outcomes to API errors. */
@Component
class ContentWriter {

    static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    static final String FILE_EMPTY = "FILE_EMPTY";
    /** Hard limit regardless of cabinet settings (spec §7: 500 MB configurable). */
    static final long HARD_LIMIT_MB = 500;
    private static final long MB = 1024L * 1024L;

    private final ContentStore contentStore;

    ContentWriter(ContentStore contentStore) {
        this.contentStore = contentStore;
    }

    StoredContent store(InputStream content, int cabinetLimitMb) {
        long limitMb = Math.min(cabinetLimitMb, HARD_LIMIT_MB);
        RequestContext caller = RequestContext.require();
        try {
            return contentStore.store(caller.tenantId(), caller.tenantCode(), content, limitMb * MB);
        } catch (ContentTooLargeException e) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, FILE_TOO_LARGE,
                    "The file is larger than the cabinet's limit of " + limitMb + " MB.");
        } catch (EmptyContentException e) {
            throw ApiException.unprocessable(FILE_EMPTY, "The file is empty.");
        }
    }

    /** Best effort: a blob left behind is found by the orphan reconciliation job. */
    void discard(StoredContent stored) {
        if (stored == null) {
            return;
        }
        try {
            contentStore.delete(stored.blobKey());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ContentWriter.class)
                    .warn("Could not delete an unused blob; the reconciliation job will remove it");
        }
    }
}
