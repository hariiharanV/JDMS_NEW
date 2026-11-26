package com.jmr.jdms.worker.capture;

import com.jmr.jdms.contracts.CaptureJobPayload;
import com.jmr.jdms.contracts.CaptureOutcome;
import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.platform.jobs.PermanentJobFailure;
import com.jmr.jdms.worker.config.WorkerProperties;
import com.jmr.jdms.worker.infra.CaptureStore;
import com.jmr.jdms.worker.infra.CaptureStore.CaptureToRead;
import com.jmr.jdms.worker.infra.ClamAvClient;
import com.jmr.jdms.worker.infra.TikaClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a capture for assisted indexing (ADR-0024) in one job: antivirus (ClamAV), then text (Tika), then OCR when
 * there is no text layer, then {@link FieldSuggester}. The suggestions go into a new encrypted blob; the result is
 * reported to the core with a {@code CAPTURE_RESULT} job in the transaction that completes this one. The file itself
 * is only read. Runs the same limits as the processing pipeline (size, Tika timeouts and text cap).
 */
@Component
class CaptureReader implements JobHandler {

    static final Set<String> OCR_TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/tiff",
            "image/bmp", "image/gif");
    private static final int MIN_TEXT = 20;

    private final CaptureStore captures;
    private final EnvelopeEncryption encryption;
    private final ClamAvClient clamav;
    private final TikaClient tika;
    private final JsonMapper jsonMapper;
    private final long maxInputBytes;

    CaptureReader(CaptureStore captures, EnvelopeEncryption encryption, ClamAvClient clamav, TikaClient tika,
            JsonMapper jsonMapper, WorkerProperties properties) {
        this.captures = captures;
        this.encryption = encryption;
        this.clamav = clamav;
        this.tika = tika;
        this.jsonMapper = jsonMapper;
        this.maxInputBytes = properties.maxInputMb() * 1024 * 1024;
    }

    @Override
    public Set<String> types() {
        return Set.of(JobType.CAPTURE.name());
    }

    @Override
    public JobOutcome handle(ClaimedJob job) throws IOException {
        CaptureJobPayload payload = payload(job);
        Optional<CaptureToRead> found = captures.find(job.tenantId(), payload.captureId());
        if (found.isEmpty() || !"PENDING".equals(found.get().status()) || found.get().content() == null) {
            return JobOutcome.done(); // discarded, expired or already read (duplicate delivery)
        }
        CaptureToRead capture = found.get();
        if (capture.sizeBytes() > maxInputBytes) {
            throw new PermanentJobFailure("File larger than the processing limit of " + maxInputBytes / 1024 / 1024
                    + " MB");
        }
        ClamAvClient.Verdict verdict;
        try (InputStream content = open(payload, capture)) {
            verdict = clamav.scan(content);
        }
        if (verdict.infected()) {
            return JobOutcome.then(result(payload, CaptureOutcome.INFECTED, verdict.signature(), null, false, null,
                    0));
        }
        String type = baseType(tika.detect(() -> open(payload, capture)));
        String text = tika.extract(() -> open(payload, capture)).text();
        boolean ocr = false;
        if (!meaningful(text) && OCR_TYPES.contains(type)) {
            text = tika.ocr(() -> open(payload, capture), "application/pdf".equals(type)).text();
            ocr = true;
        }
        List<FieldSuggestion> suggestions = new FieldSuggester(captures.fields(job.tenantId(),
                capture.documentTypeId())).suggest(text);
        RenditionBlob stored = suggestions.isEmpty() ? null : store(payload, capture, suggestions);
        return JobOutcome.then(result(payload, CaptureOutcome.DONE, null, type, ocr, stored, suggestions.size()));
    }

    /** Reported to the core so the capture does not stay "reading" until it expires. */
    @Override
    public List<NewJob> onGiveUp(ClaimedJob job, String reason) {
        return List.of(result(payload(job), CaptureOutcome.FAILED, reason, null, false, null, 0));
    }

    private RenditionBlob store(CaptureJobPayload payload, CaptureToRead capture, List<FieldSuggestion> suggestions) {
        byte[] json = jsonMapper.writeValueAsString(suggestions).getBytes(StandardCharsets.UTF_8);
        StoredBlob blob = encryption.store(payload.tenantId(), capture.tenantCode(), new ByteArrayInputStream(json),
                Long.MAX_VALUE);
        return new RenditionBlob("SUGGESTIONS", "application/json", blob.blobKey(), blob.sha256(), blob.sizeBytes(),
                blob.wrappedKey(), blob.keyRef());
    }

    private InputStream open(CaptureJobPayload payload, CaptureToRead capture) {
        return encryption.open(payload.tenantId(), capture.content());
    }

    private NewJob result(CaptureJobPayload payload, CaptureOutcome outcome, String detail, String detectedMimeType,
            boolean ocr, RenditionBlob suggestions, int count) {
        return new NewJob(payload.tenantId(), JobType.CAPTURE_RESULT.name(), jsonMapper.writeValueAsString(
                new CaptureResult(payload.tenantId(), payload.captureId(), outcome, detail, detectedMimeType, ocr,
                        suggestions, count)));
    }

    private CaptureJobPayload payload(ClaimedJob job) {
        CaptureJobPayload payload = jsonMapper.readValue(job.payload(), CaptureJobPayload.class);
        if (!job.tenantId().equals(payload.tenantId())) {
            throw new PermanentJobFailure("Job tenant and payload tenant differ");
        }
        return payload;
    }

    private static boolean meaningful(String text) {
        return text.codePoints().filter(Character::isLetterOrDigit).limit(MIN_TEXT).count() >= MIN_TEXT;
    }

    private static String baseType(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        return (semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}
