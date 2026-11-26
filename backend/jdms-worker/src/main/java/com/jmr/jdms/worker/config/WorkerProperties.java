package com.jmr.jdms.worker.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Worker settings ({@code jdms.worker.*}). Limits keep untrusted files from exhausting the worker (CLAUDE.md rule 6):
 * input size, per-call timeouts, extracted text and rendition sizes, thumbnail pixels.
 *
 * @param autoStart     poll the queue from startup (tests drive the runner themselves)
 * @param maxInputMb    larger files are not processed (they fail permanently)
 */
@ConfigurationProperties("jdms.worker")
public record WorkerProperties(
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("4") int concurrency,
        @DefaultValue("PT2M") Duration lease,
        @DefaultValue("PT30S") Duration backoffBase,
        @DefaultValue("PT30M") Duration backoffMax,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("100") long maxInputMb,
        @DefaultValue ClamAv clamav,
        @DefaultValue Tika tika,
        @DefaultValue Gotenberg gotenberg,
        @DefaultValue Thumbnail thumbnail) {

    public record ClamAv(@DefaultValue("localhost") String host, @DefaultValue("3310") int port,
            @DefaultValue("PT2M") Duration timeout) {
    }

    /**
     * @param maxTextChars extracted text beyond this is cut off
     * @param pdfOcrDpi    resolution PDF pages are rendered at for OCR
     */
    public record Tika(@DefaultValue("http://localhost:9998") URI url, @DefaultValue("PT5M") Duration timeout,
            @DefaultValue("5000000") int maxTextChars, @DefaultValue("eng+ara") String ocrLanguages,
            @DefaultValue("150") int pdfOcrDpi) {
    }

    public record Gotenberg(@DefaultValue("http://localhost:3300") URI url, @DefaultValue("PT5M") Duration timeout,
            @DefaultValue("200") long maxPdfMb) {
    }

    /**
     * @param maxPixels  images larger than this (width × height) get no thumbnail
     * @param maxInputMb PDFs larger than this get no thumbnail (PDFBox holds them in memory)
     */
    public record Thumbnail(@DefaultValue("256") int size, @DefaultValue("PT30S") Duration timeout,
            @DefaultValue("100000000") long maxPixels, @DefaultValue("50") long maxInputMb) {
    }
}
