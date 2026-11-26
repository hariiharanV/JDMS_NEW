package com.jmr.jdms.worker.infra;

import com.jmr.jdms.platform.jobs.PermanentJobFailure;
import com.jmr.jdms.worker.config.WorkerProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Gotenberg (LibreOffice) converts office documents and plain text to PDF for preview. The file is streamed as a
 * multipart upload named by its detected type (LibreOffice picks its import filter by extension); the PDF is read with
 * a size cap.
 */
@Component
public class GotenbergClient {

    /** Detected types LibreOffice converts, with the extension it expects. */
    static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.oasis.opendocument.text", "odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "odp"),
            Map.entry("application/rtf", "rtf"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/csv", "csv"));

    private final WorkerProperties.Gotenberg settings;
    private final HttpClient http;

    GotenbergClient(WorkerProperties properties) {
        this.settings = properties.gotenberg();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The extension for a detected type, if Gotenberg can convert it. */
    public static Optional<String> extensionFor(String detectedMimeType) {
        return Optional.ofNullable(EXTENSIONS.get(baseType(detectedMimeType)));
    }

    /**
     * @return the PDF bytes
     * @throws PermanentJobFailure when the conversion fails on the file itself (HTTP 4xx) or the PDF is too large
     */
    public byte[] convert(Supplier<InputStream> content, String extension) throws IOException {
        String boundary = "jdms" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"document."
                + extension + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url() + "/forms/libreoffice/convert"))
                .timeout(settings.timeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new SequenceInputStream(
                        Collections.enumeration(List.of(new ByteArrayInputStream(head), content.get(),
                                new ByteArrayInputStream(tail))))))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() >= 400 && response.statusCode() < 500 && response.statusCode() != 429) {
                throw new PermanentJobFailure("PDF conversion failed (HTTP " + response.statusCode() + ")");
            }
            if (response.statusCode() >= 300) {
                throw new IOException("Gotenberg failed with HTTP " + response.statusCode());
            }
            Bounded.Read pdf = Bounded.read(body, settings.maxPdfMb() * 1024 * 1024);
            if (pdf.truncated()) {
                throw new PermanentJobFailure("PDF rendition larger than " + settings.maxPdfMb() + " MB");
            }
            return pdf.bytes();
        }
    }

    static String baseType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int semicolon = mimeType.indexOf(';');
        return (semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)).strip().toLowerCase();
    }
}
