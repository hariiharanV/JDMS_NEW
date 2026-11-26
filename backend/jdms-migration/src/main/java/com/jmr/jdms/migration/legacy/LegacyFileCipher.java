package com.jmr.jdms.migration.legacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The only place the legacy JDMS secret is used (CLAUDE.md rule 11, ADR-0020). Legacy files are AES-128 in ECB mode
 * with PKCS#5 padding, keyed with the first 16 bytes of SHA-1 of the secret, as the legacy code did (reference §5;
 * kept only to read old files: never used to write anything). The secret is read from a file at run time; neither it
 * nor the derived key is ever logged, printed, returned or put in an exception message.
 */
public final class LegacyFileCipher {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private final SecretKeySpec key;

    private LegacyFileCipher(SecretKeySpec key) {
        this.key = key;
    }

    /** Reads the secret (surrounding whitespace ignored) and derives the legacy key; the file content is wiped. */
    public static LegacyFileCipher fromSecretFile(Path secretFile) {
        if (secretFile == null || !Files.isRegularFile(secretFile)) {
            throw new IllegalStateException("The legacy secret file is not configured or does not exist");
        }
        byte[] raw = null;
        byte[] digest = null;
        try {
            raw = Files.readAllBytes(secretFile);
            String secret = new String(raw, StandardCharsets.UTF_8).strip();
            digest = MessageDigest.getInstance("SHA-1").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new LegacyFileCipher(new SecretKeySpec(Arrays.copyOf(digest, 16), "AES"));
        } catch (IOException e) {
            throw new UncheckedIOException("The legacy secret file could not be read", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        } finally {
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    /** Decrypts one legacy file; a wrong key or a damaged file fails with {@link UndecryptableFileException}. */
    public byte[] decrypt(byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException e) {
            // the cause names only the failure (e.g. bad padding), never key material
            throw new UndecryptableFileException(e.getClass().getSimpleName());
        }
    }

    @Override
    public String toString() {
        return "LegacyFileCipher[key not shown]";
    }

    /** The file could not be decrypted with the legacy key (wrong key, truncated or damaged file). */
    public static final class UndecryptableFileException extends RuntimeException {

        UndecryptableFileException(String reason) {
            super("The legacy file could not be decrypted (" + reason + ")", null, false, false);
        }
    }
}
