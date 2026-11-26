package com.jmr.jdms.worker.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads responses of external services with a size cap, so no response can exhaust the worker's memory. */
public final class Bounded {

    /** The bytes read, and whether more were available than the cap. */
    public record Read(byte[] bytes, boolean truncated) {
    }

    private Bounded() {
    }

    public static Read read(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                out.write(buffer, 0, (int) (maxBytes - total));
                return new Read(out.toByteArray(), true);
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return new Read(out.toByteArray(), false);
    }
}
