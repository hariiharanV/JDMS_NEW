package com.jmr.jdms.migration.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.migration.legacy.LegacyFileCipher.UndecryptableFileException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyFileCipherTest {

    static final String SECRET = "legacy-secret-for-tests-" + System.nanoTime();

    @TempDir Path dir;

    @Test
    void decryptsWhatLegacyJdmsEncrypted() throws Exception {
        byte[] content = "%PDF-1.4 legacy content".getBytes(StandardCharsets.UTF_8);
        LegacyFileCipher cipher = LegacyFileCipher.fromSecretFile(secretFile(SECRET + "\n"));

        assertThat(cipher.decrypt(LegacyEncryption.encrypt(SECRET, content))).isEqualTo(content);
    }

    @Test
    void aWrongKeyOrADamagedFileIsUndecryptableWithoutRevealingAnything() throws Exception {
        LegacyFileCipher cipher = LegacyFileCipher.fromSecretFile(secretFile(SECRET));
        byte[] encrypted = LegacyEncryption.encrypt(SECRET, new byte[100]);

        assertThatThrownBy(() -> cipher.decrypt(Arrays.copyOf(encrypted, encrypted.length - 3)))
                .isInstanceOf(UndecryptableFileException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET)
                        .doesNotContain(HexFormat.of().formatHex(LegacyEncryption.key(SECRET))));
        assertThat(cipher.toString()).doesNotContain(SECRET).isEqualTo("LegacyFileCipher[key not shown]");
    }

    @Test
    void aMissingSecretFileStopsTheRun() {
        assertThatThrownBy(() -> LegacyFileCipher.fromSecretFile(dir.resolve("absent")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(SECRET);
    }

    private Path secretFile(String content) throws Exception {
        Path file = dir.resolve("legacy.secret");
        Files.writeString(file, content);
        return file;
    }
}
