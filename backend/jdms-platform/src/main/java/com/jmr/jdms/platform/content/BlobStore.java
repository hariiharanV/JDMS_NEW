package com.jmr.jdms.platform.content;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.function.Consumer;

/** Raw object storage. Holds only ciphertext; never sees plaintext or file names. */
public interface BlobStore {

    /** A streaming writer; {@link #abort()} discards everything written. Closing completes the object. */
    abstract class BlobWriter extends OutputStream {

        public abstract void abort();
    }

    BlobWriter openWriter(String key);

    /** @throws BlobNotFoundException when the key does not exist */
    InputStream read(String key);

    void delete(String key);

    /** Calls the consumer for every object (key and last-modified time). */
    void forEach(Consumer<BlobInfo> consumer);

    record BlobInfo(String key, Instant lastModified) {
    }

    class BlobNotFoundException extends RuntimeException {

        public BlobNotFoundException(String key) {
            super("Blob not found");
        }
    }
}
