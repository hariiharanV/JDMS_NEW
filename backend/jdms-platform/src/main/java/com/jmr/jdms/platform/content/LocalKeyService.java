package com.jmr.jdms.platform.content;

import com.google.crypto.tink.subtle.Hkdf;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local KMS stub (PoC only). The tenant key is derived with HKDF-SHA256 from a master key supplied by the
 * environment; data keys are wrapped with AES-256-GCM, bound to the tenant through the associated data. Key material
 * is never logged.
 */
public class LocalKeyService implements KeyService {

    public static final String KEY_REF = "local:v1";

    private static final Logger log = LoggerFactory.getLogger(LocalKeyService.class);
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    /**
     * @param localMasterKey       base64 of 32 random bytes ({@code JDMS_LOCAL_KMS_MASTER_KEY}); never in files
     * @param generateEphemeralKey tests only: generate a random master key (data is unreadable afterwards)
     */
    public LocalKeyService(String localMasterKey, boolean generateEphemeralKey) {
        if (localMasterKey != null && !localMasterKey.isBlank()) {
            this.masterKey = Base64.getDecoder().decode(localMasterKey.trim());
        } else if (generateEphemeralKey) {
            this.masterKey = new byte[32];
            RANDOM.nextBytes(masterKey);
            log.warn("Local KMS stub uses an ephemeral master key: stored content is unreadable after restart");
        } else {
            throw new IllegalStateException("jdms.kms.local-master-key (JDMS_LOCAL_KMS_MASTER_KEY) is required");
        }
        if (masterKey.length != 32) {
            throw new IllegalStateException("The local KMS master key must be 32 bytes (base64)");
        }
    }

    @Override
    public WrappedKey wrap(UUID tenantId, byte[] dataKey) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, tenantId, nonce);
            byte[] sealed = cipher.doFinal(dataKey);
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return new WrappedKey(KEY_REF, Base64.getEncoder().encodeToString(out));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not wrap data key", e);
        }
    }

    @Override
    public byte[] unwrap(UUID tenantId, String keyRef, String wrapped) {
        if (!KEY_REF.equals(keyRef)) {
            throw new IllegalArgumentException("Unknown key reference");
        }
        try {
            byte[] in = Base64.getDecoder().decode(wrapped);
            byte[] nonce = Arrays.copyOfRange(in, 0, NONCE_BYTES);
            return cipher(Cipher.DECRYPT_MODE, tenantId, nonce).doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not unwrap data key", e);
        }
    }

    private Cipher cipher(int mode, UUID tenantId, byte[] nonce) throws GeneralSecurityException {
        byte[] tenantKey = Hkdf.computeHkdf("HMACSHA256", masterKey, new byte[0],
                ("jdms-tenant-key:" + tenantId).getBytes(StandardCharsets.UTF_8), 32);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(tenantKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("jdms-data-key:" + tenantId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
