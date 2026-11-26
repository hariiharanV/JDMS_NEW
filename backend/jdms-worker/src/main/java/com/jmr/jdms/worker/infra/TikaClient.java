package com.jmr.jdms.worker.infra;

import com.jmr.jdms.platform.jobs.PermanentJobFailure;
import com.jmr.jdms.worker.config.WorkerProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Apache Tika server (a separate process that does the parsing, with its own watchdog): content type detection,
 * text extraction and OCR with Tesseract. Responses are read with a cap; the client never sends the uploader's
 * file name or claimed type, so Tika detects from the bytes.
 */
@Component
public class TikaClient {

    /** Extracted text (possibly cut at the configured maximum); empty when there is none. */
    public record Text(String text, boolean truncated) {
    }

    private final WorkerProperties.Tika settings;
    private final HttpClient http;

    TikaClient(WorkerProperties properties) {
        this.settings = properties.tika();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The content type detected from the bytes (magic numbers and container structure). */
    public String detect(Supplier<InputStream> content) throws IOException {
        HttpResponse<InputStream> response = send(request("/detect/stream", content).header("Accept", "text/plain")
                .build());
        try (InputStream body = response.body()) {
            check(response.statusCode(), "detection");
            String type = new String(Bounded.read(body, 200).bytes(), StandardCharsets.US_ASCII).strip();
            return type.isEmpty() ? "application/octet-stream" : type;
        }
    }

    /** Text of digital documents; OCR is skipped. */
    public Text extract(Supplier<InputStream> content) throws IOException {
        return text(request("/tika", content).header("X-Tika-OCRskipOcr", "true"));
    }

    /** OCR in the configured languages; PDF pages are rendered at the configured resolution first. */
    public Text ocr(Supplier<InputStream> content, boolean pdf) throws IOException {
        HttpRequest.Builder request = request("/tika", content)
                .header("X-Tika-OCRLanguage", settings.ocrLanguages());
        if (pdf) {
            request.header("X-Tika-PDFOcrStrategy", "ocr_only")
                    .header("X-Tika-PDFOcrDPI", String.valueOf(settings.pdfOcrDpi()));
        }
        return text(request);
    }

    private Text text(HttpRequest.Builder request) throws IOException {
        HttpResponse<InputStream> response = send(request.header("Accept", "text/plain; charset=UTF-8").build());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 204 || response.statusCode() == 415) {
                return new Text("", false); // no text, or a type Tika has no parser for
            }
            check(response.statusCode(), "text extraction");
            // UTF-8 is at most 4 bytes per char; the cut may split the last character, which is dropped
            Bounded.Read read = Bounded.read(body, 4L * settings.maxTextChars());
            String text = new String(read.bytes(), StandardCharsets.UTF_8);
            boolean truncated = read.truncated() || text.length() > settings.maxTextChars();
            return new Text(text.length() > settings.maxTextChars() ? text.substring(0, settings.maxTextChars())
                    : text, truncated);
        }
    }

    private HttpRequest.Builder request(String path, Supplier<InputStream> content) {
        return HttpRequest.newBuilder(URI.create(settings.url() + path)).timeout(settings.timeout())
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(content));
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** 422: Tika could not parse the file (corrupt, encrypted): retrying will not help. 5xx: retried. */
    private static void check(int status, String what) throws IOException {
        if (status == 422) {
            throw new PermanentJobFailure("Tika could not parse the file (" + what + ", HTTP 422)");
        }
        if (status >= 400) {
            throw new IOException("Tika " + what + " failed with HTTP " + status);
        }
    }
}
