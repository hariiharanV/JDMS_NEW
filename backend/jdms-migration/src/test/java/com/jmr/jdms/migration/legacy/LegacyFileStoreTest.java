package com.jmr.jdms.migration.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.migration.legacy.LegacyFileStore.FileTooLargeException;
import com.jmr.jdms.migration.legacy.LegacyFileStore.IllegalLegacyPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyFileStoreTest {

    @TempDir Path root;

    @Test
    void legacyPathsResolveInsideTheRootOnly() throws Exception {
        LegacyFileStore store = new LegacyFileStore(root, 10);
        Path file = store.resolve("base", "/kyc\\2019/", "a.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});

        assertThat(store.relative(file)).isEqualTo("base/kyc/2019/a.pdf");
        assertThat(store.read(file)).hasValueSatisfying(content -> assertThat(content).hasSize(3));
        assertThat(store.read(root.resolve("missing.pdf"))).isEmpty();
        assertThatThrownBy(() -> store.resolve("base", "../../etc", "passwd"))
                .isInstanceOf(IllegalLegacyPathException.class);
    }

    @Test
    void oversizedFilesAreRefused() throws Exception {
        LegacyFileStore store = new LegacyFileStore(root, 2);
        Path file = root.resolve("big.pdf");
        Files.write(file, new byte[3]);

        assertThatThrownBy(() -> store.read(file)).isInstanceOf(FileTooLargeException.class);
    }
}
