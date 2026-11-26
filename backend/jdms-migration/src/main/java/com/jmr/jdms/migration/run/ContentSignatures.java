package com.jmr.jdms.migration.run;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Verify by opening the decrypted file" (reference §5): the decrypted bytes must start like a file of the legacy
 * extension. A wrong key that happens to unpad, or a damaged file, fails here instead of being stored. Only the
 * leading bytes are looked at: the file is never parsed in this tool (CLAUDE.md rule 6).
 */
final class ContentSignatures {

    private static final Map<String, List<byte[]>> SIGNATURES = Map.of(
            "pdf", List.of(bytes('%', 'P', 'D', 'F')),
            "tif", List.of(bytes('I', 'I', 0x2A, 0x00), bytes('M', 'M', 0x00, 0x2A)),
            "tiff", List.of(bytes('I', 'I', 0x2A, 0x00), bytes('M', 'M', 0x00, 0x2A)),
            "jpg", List.of(bytes(0xFF, 0xD8, 0xFF)),
            "jpeg", List.of(bytes(0xFF, 0xD8, 0xFF)),
            "png", List.of(bytes(0x89, 'P', 'N', 'G')),
            "doc", List.of(bytes(0xD0, 0xCF, 0x11, 0xE0)),
            "docx", List.of(bytes('P', 'K', 0x03, 0x04)),
            "xlsx", List.of(bytes('P', 'K', 0x03, 0x04)));

    private ContentSignatures() {
    }

    /** True for extensions without a known signature (nothing to check). */
    static boolean matches(String extension, byte[] content) {
        List<byte[]> signatures = SIGNATURES.get(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
        return signatures == null || signatures.stream().anyMatch(signature -> content.length >= signature.length
                && Arrays.equals(content, 0, signature.length, signature, 0, signature.length));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
