package com.jmr.jdms.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VerifiedContentTest {

    @Test
    void matchingContentIsWrittenCompletely() throws Exception {
        byte[] content = random(200_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (VerifiedContent verified = new VerifiedContent(new ByteArrayInputStream(content), sha256(content),
                () -> { })) {
            verified.writeTo(out);
        }

        assertThat(out.toByteArray()).isEqualTo(content);
    }

    @Test
    void mismatchHoldsBackTheLastChunkAndReportsTheFailure() throws Exception {
        byte[] content = random(200_000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean reported = new AtomicBoolean();

        VerifiedContent verified = new VerifiedContent(new ByteArrayInputStream(content), "0".repeat(64),
                () -> reported.set(true));
        assertThatThrownBy(() -> verified.writeTo(out)).isInstanceOf(IOException.class);

        assertThat(reported).isTrue();
        assertThat(out.size()).isLessThan(content.length);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
