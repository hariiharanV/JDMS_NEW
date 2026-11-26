package com.jmr.jdms.document.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Decrypted content ready to stream, verified as it goes: the first chunk is already read (and authenticated) before
 * any byte reaches the client, and the last chunk is held back until the SHA-256 of the whole content matches the
 * recorded one. On failure the callback runs and the transfer is aborted, so a client never receives a complete but
 * wrong file.
 */
public final class VerifiedContent implements AutoCloseable {

    private static final int CHUNK = 64 * 1024;

    private final InputStream decrypted;
    private final String expectedSha256;
    private final Runnable onFailure;
    private final MessageDigest digest;
    private byte[] pending;
    private int pendingLength;

    VerifiedContent(InputStream decrypted, String expectedSha256, Runnable onFailure) throws IOException {
        this.decrypted = decrypted;
        this.expectedSha256 = expectedSha256;
        this.onFailure = onFailure;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.pending = new byte[CHUNK];
        this.pendingLength = readFully(pending);
    }

    public void writeTo(OutputStream out) throws IOException {
        try {
            byte[] next = new byte[CHUNK];
            int nextLength;
            while ((nextLength = readFully(next)) > 0) {
                out.write(pending, 0, pendingLength);
                byte[] swap = pending;
                pending = next;
                pendingLength = nextLength;
                next = swap;
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
                throw new IOException("SHA-256 mismatch");
            }
            out.write(pending, 0, pendingLength);
            out.flush();
        } catch (IOException e) {
            onFailure.run();
            throw e;
        }
    }

    private int readFully(byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = decrypted.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        digest.update(buffer, 0, total);
        return total;
    }

    @Override
    public void close() throws IOException {
        Arrays.fill(pending, (byte) 0);
        decrypted.close();
    }
}
