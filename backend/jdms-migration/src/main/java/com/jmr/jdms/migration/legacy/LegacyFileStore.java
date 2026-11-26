package com.jmr.jdms.migration.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads files from the legacy file tree. Legacy paths come from the legacy database and are resolved strictly inside
 * the configured root: a path that leaves it (".." or an absolute path) is refused, never followed.
 */
public final class LegacyFileStore {

    private final Path root;
    private final long maxBytes;

    public LegacyFileStore(Path root, long maxBytes) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalStateException("The legacy files root is not configured or is not a directory");
        }
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /** {@code base/folder/name} under the root, or {@link IllegalLegacyPathException}. */
    public Path resolve(String base, String folder, String name) {
        Path path = root;
        for (String part : new String[] {base, folder, name}) {
            if (part != null && !part.isBlank()) {
                path = path.resolve(part.replace('\\', '/').replaceFirst("^/+", ""));
            }
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalLegacyPathException();
        }
        return normalized;
    }

    /** The encrypted bytes; empty when the file does not exist. */
    public Optional<byte[]> read(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (content.length > maxBytes) {
                throw new FileTooLargeException();
            }
            return Optional.of(content);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("A legacy file could not be read", e);
        }
    }

    /** The path relative to the root, for the report. */
    public String relative(Path path) {
        return root.relativize(path).toString();
    }

    public static final class IllegalLegacyPathException extends RuntimeException {

        IllegalLegacyPathException() {
            super("A legacy file path points outside the legacy files root", null, false, false);
        }
    }

    public static final class FileTooLargeException extends RuntimeException {

        FileTooLargeException() {
            super("A legacy file is larger than the configured limit", null, false, false);
        }
    }
}
