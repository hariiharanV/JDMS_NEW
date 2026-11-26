package com.jmr.jdms.migration.legacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Test fixture only: encrypts files the way legacy JDMS did, to build legacy file trees for the tests. */
public final class LegacyEncryption {

    private LegacyEncryption() {
    }

    public static byte[] encrypt(String secret, byte[] content) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(secret), "AES"));
        return cipher.doFinal(content);
    }

    public static byte[] key(String secret) throws Exception {
        return Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(secret.getBytes(StandardCharsets.UTF_8)), 16);
    }
}
