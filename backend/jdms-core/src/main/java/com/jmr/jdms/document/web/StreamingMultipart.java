package com.jmr.jdms.document.web;

import com.jmr.jdms.shared.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.springframework.http.HttpStatus;

/**
 * Reads {@code multipart/form-data} as a stream (ADR-0011): text parts first (at most 64 KiB each), then the
 * {@code file} part, whose stream is handed to the caller without being buffered or written to disk. Parts after the
 * file are not read. Uploaded content is never parsed here (CLAUDE.md rule 6).
 */
final class StreamingMultipart {

    static final String FILE_PART = "file";
    private static final long MAX_REQUEST_BYTES = 501L * 1024 * 1024;
    private static final int MAX_FIELD_BYTES = 64 * 1024;
    /** Versions of one imported legacy document, plus the metadata part. */
    private static final int MAX_IMPORT_FILES = 101;

    private StreamingMultipart() {
    }

    record FilePart(String fileName, String contentType, InputStream stream) {
    }

    @FunctionalInterface
    interface Handler<T> {
        T handle(Map<String, String> fields, FilePart file);
    }

    /** Receives the file parts of a request one by one, after its text parts. */
    interface FileSink<T> {

        /** False to stop reading: the remaining parts are not needed. */
        boolean wantsFiles();

        void accept(FilePart file);

        T finish();
    }

    /**
     * Several files (legacy import, ADR-0020): {@code start} receives the text parts when the first file part
     * arrives (or at the end when there is none) and returns the sink for the files. Each file stream is handed over
     * in order without buffering; a part's stream is valid only during {@link FileSink#accept}.
     */
    static <T> T readFiles(HttpServletRequest request, Function<Map<String, String>, FileSink<T>> start) {
        JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload = new JakartaServletFileUpload<>();
        upload.setMaxSize(MAX_REQUEST_BYTES);
        upload.setMaxFileCount(MAX_IMPORT_FILES);
        Map<String, String> fields = new HashMap<>();
        FileSink<T> sink = null;
        try {
            FileItemInputIterator items = upload.getItemIterator(request);
            while (items.hasNext()) {
                FileItemInput item = items.next();
                if (FILE_PART.equals(item.getFieldName())) {
                    if (sink == null) {
                        sink = start.apply(fields);
                    }
                    if (!sink.wantsFiles()) {
                        return sink.finish();
                    }
                    try (InputStream stream = item.getInputStream()) {
                        sink.accept(new FilePart(item.getName(), item.getContentType(), stream));
                    }
                    continue;
                }
                if (sink != null) {
                    throw ApiException.unprocessable("PART_ORDER_INVALID", "Text parts must come before the files.");
                }
                try (InputStream stream = item.getInputStream()) {
                    byte[] value = stream.readNBytes(MAX_FIELD_BYTES + 1);
                    if (value.length > MAX_FIELD_BYTES) {
                        throw ApiException.unprocessable("PART_TOO_LARGE", "Part " + item.getFieldName()
                                + " is larger than 64 KiB.");
                    }
                    fields.put(item.getFieldName(), new String(value, StandardCharsets.UTF_8));
                }
            }
            return (sink == null ? start.apply(fields) : sink).finish();
        } catch (FileUploadException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MULTIPART_INVALID", "The multipart request is invalid.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isMultipart(HttpServletRequest request) {
        return JakartaServletFileUpload.isMultipartContent(request);
    }

    /** Calls the handler once: with the file part when present (it must come after the text parts), else null. */
    static <T> T read(HttpServletRequest request, Handler<T> handler) {
        JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload = new JakartaServletFileUpload<>();
        upload.setMaxSize(MAX_REQUEST_BYTES);
        upload.setMaxFileCount(10);
        Map<String, String> fields = new HashMap<>();
        try {
            FileItemInputIterator items = upload.getItemIterator(request);
            while (items.hasNext()) {
                FileItemInput item = items.next();
                if (FILE_PART.equals(item.getFieldName())) {
                    try (InputStream stream = item.getInputStream()) {
                        return handler.handle(fields, new FilePart(item.getName(), item.getContentType(), stream));
                    }
                }
                try (InputStream stream = item.getInputStream()) {
                    byte[] value = stream.readNBytes(MAX_FIELD_BYTES + 1);
                    if (value.length > MAX_FIELD_BYTES) {
                        throw ApiException.unprocessable("PART_TOO_LARGE", "Part " + item.getFieldName()
                                + " is larger than 64 KiB.");
                    }
                    fields.put(item.getFieldName(), new String(value, StandardCharsets.UTF_8));
                }
            }
            return handler.handle(fields, null);
        } catch (FileUploadException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MULTIPART_INVALID", "The multipart request is invalid.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
