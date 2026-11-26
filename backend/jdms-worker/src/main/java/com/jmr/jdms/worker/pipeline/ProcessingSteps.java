package com.jmr.jdms.worker.pipeline;

import com.jmr.jdms.contracts.JobType;
import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.ProcessingStepPayload;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.contracts.VersionJobPayload;
import com.jmr.jdms.platform.content.EnvelopeEncryption;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.ClaimedJob;
import com.jmr.jdms.platform.jobs.JobHandler;
import com.jmr.jdms.platform.jobs.JobOutcome;
import com.jmr.jdms.platform.jobs.NewJob;
import com.jmr.jdms.platform.jobs.PermanentJobFailure;
import com.jmr.jdms.worker.config.WorkerProperties;
import com.jmr.jdms.worker.infra.ClamAvClient;
import com.jmr.jdms.worker.infra.GotenbergClient;
import com.jmr.jdms.worker.infra.TikaClient;
import com.jmr.jdms.worker.infra.VersionStore;
import com.jmr.jdms.worker.infra.VersionStore.VersionToProcess;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The processing pipeline (spec §4.2): SCAN (ClamAV) → EXTRACT (type detection and text, Tika) → OCR (Tesseract via
 * Tika, only for images and PDFs without a text layer) → RENDER (PDF via Gotenberg for office files, PNG thumbnail).
 * Each step is a job of its own, retried with backoff, and reports to the core with a {@code PROCESSING_RESULT} job
 * enqueued in the same transaction that completes the step. Renditions are new encrypted blobs; the original is only
 * ever read. A step whose rendition the core already holds does not redo it (duplicate delivery).
 */
@Component
class ProcessingSteps implements JobHandler {

    static final Set<String> OCR_TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/tiff",
            "image/bmp", "image/gif");
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp", "image/tiff");
    /** Fewer letters or digits than this: no text layer worth indexing, try OCR. */
    static final int MIN_TEXT = 20;
    private static final String PDF = "PDF";
    private static final String THUMBNAIL = "THUMBNAIL";
    private static final String TEXT = "TEXT";

    private final VersionStore versions;
    private final EnvelopeEncryption encryption;
    private final ClamAvClient clamav;
    private final TikaClient tika;
    private final GotenbergClient gotenberg;
    private final Thumbnailer thumbnailer;
    private final JsonMapper jsonMapper;
    private final long maxInputBytes;

    ProcessingSteps(VersionStore versions, EnvelopeEncryption encryption, ClamAvClient clamav, TikaClient tika,
            GotenbergClient gotenberg, Thumbnailer thumbnailer, JsonMapper jsonMapper, WorkerProperties properties) {
        this.versions = versions;
        this.encryption = encryption;
        this.clamav = clamav;
        this.tika = tika;
        this.gotenberg = gotenberg;
        this.thumbnailer = thumbnailer;
        this.jsonMapper = jsonMapper;
        this.maxInputBytes = properties.maxInputMb() * 1024 * 1024;
    }

    @Override
    public Set<String> types() {
        return Set.of(JobType.SCAN.name(), JobType.EXTRACT.name(), JobType.OCR.name(), JobType.RENDER.name());
    }

    @Override
    public JobOutcome handle(ClaimedJob job) throws IOException {
        ProcessingStepPayload step = payload(job);
        Optional<VersionToProcess> found = versions.find(job.tenantId(), step.documentId(), step.versionId());
        if (found.isEmpty() || "QUARANTINED".equals(found.get().processingStatus())) {
            return JobOutcome.done(); // gone, or infected: nothing more to do
        }
        VersionToProcess version = found.get();
        return switch (JobType.valueOf(job.type())) {
            case SCAN -> scan(step, version);
            case EXTRACT -> extract(step, version);
            case OCR -> ocr(step, version);
            case RENDER -> render(step, version);
            default -> throw new IllegalStateException("Unexpected job type " + job.type());
        };
    }

    /** Reported to the core so the version does not stay "processing" forever. */
    @Override
    public List<NewJob> onGiveUp(ClaimedJob job, String reason) {
        ProcessingStepPayload step = payload(job);
        return List.of(result(step, ProcessingStep.valueOf(job.type()), ProcessingOutcome.FAILED, reason,
                step.detectedMimeType(), null, List.of(), false, true));
    }

    private JobOutcome scan(ProcessingStepPayload step, VersionToProcess version) throws IOException {
        if ("DONE".equals(version.processingStatus())) {
            return JobOutcome.done(); // a duplicate of a completed pipeline
        }
        if (version.sizeBytes() > maxInputBytes) {
            throw new PermanentJobFailure("File larger than the processing limit of " + maxInputBytes / 1024 / 1024
                    + " MB");
        }
        ClamAvClient.Verdict verdict;
        try (InputStream content = open(step, version)) {
            verdict = clamav.scan(content);
        }
        if (verdict.infected()) {
            return JobOutcome.then(result(step, ProcessingStep.SCAN, ProcessingOutcome.INFECTED, verdict.signature(),
                    null, null, List.of(), false, true));
        }
        return JobOutcome.then(result(step, ProcessingStep.SCAN, ProcessingOutcome.CLEAN, null, null, null,
                List.of(), false, false), next(JobType.EXTRACT, step, null));
    }

    private JobOutcome extract(ProcessingStepPayload step, VersionToProcess version) throws IOException {
        String detected = tika.detect(() -> open(step, version));
        String type = baseType(detected);
        List<RenditionBlob> renditions = new ArrayList<>();
        boolean needsOcr = false;
        if (!versions.hasRendition(step.tenantId(), step.versionId(), TEXT)) {
            TikaClient.Text text = tika.extract(() -> open(step, version));
            if (meaningful(text.text())) {
                renditions.add(storeText(step, version, text.text()));
            } else {
                needsOcr = OCR_TYPES.contains(type);
            }
        }
        return JobOutcome.then(result(step, ProcessingStep.EXTRACT, ProcessingOutcome.DONE, null, type, null,
                renditions, false, false), next(needsOcr ? JobType.OCR : JobType.RENDER, step, type));
    }

    private JobOutcome ocr(ProcessingStepPayload step, VersionToProcess version) throws IOException {
        List<RenditionBlob> renditions = new ArrayList<>();
        if (!versions.hasRendition(step.tenantId(), step.versionId(), TEXT)) {
            TikaClient.Text text = tika.ocr(() -> open(step, version), "application/pdf".equals(
                    step.detectedMimeType()));
            if (!text.text().isBlank()) {
                renditions.add(storeText(step, version, text.text()));
            }
        }
        return JobOutcome.then(result(step, ProcessingStep.OCR, ProcessingOutcome.DONE, null,
                step.detectedMimeType(), null, renditions, true, false),
                next(JobType.RENDER, step, step.detectedMimeType()));
    }

    private JobOutcome render(ProcessingStepPayload step, VersionToProcess version) throws IOException {
        String type = step.detectedMimeType() == null ? "" : step.detectedMimeType();
        List<RenditionBlob> renditions = new ArrayList<>();
        Integer pageCount = null;
        boolean havePdf = versions.hasRendition(step.tenantId(), step.versionId(), PDF);
        boolean haveThumbnail = versions.hasRendition(step.tenantId(), step.versionId(), THUMBNAIL);
        Optional<String> extension = GotenbergClient.extensionFor(type);
        if (type.equals("application/pdf") && !haveThumbnail) {
            Thumbnailer.Result thumbnail = thumbnailer.fromPdf(readAll(step, version));
            pageCount = thumbnail.pageCount();
            thumbnail.png().ifPresent(png -> renditions.add(storeThumbnail(step, version, png)));
        } else if (IMAGE_TYPES.contains(type) && !haveThumbnail) {
            Thumbnailer.Result thumbnail = thumbnailer.fromImage(readAll(step, version));
            pageCount = thumbnail.pageCount();
            thumbnail.png().ifPresent(png -> renditions.add(storeThumbnail(step, version, png)));
        } else if (extension.isPresent() && !(havePdf && haveThumbnail)) {
            byte[] pdf = gotenberg.convert(() -> open(step, version), extension.get());
            if (!havePdf) {
                renditions.add(store(step, version, PDF, "application/pdf", pdf));
            }
            Thumbnailer.Result thumbnail = thumbnailer.fromPdf(pdf);
            pageCount = thumbnail.pageCount();
            if (!haveThumbnail) {
                thumbnail.png().ifPresent(png -> renditions.add(storeThumbnail(step, version, png)));
            }
        }
        return JobOutcome.then(result(step, ProcessingStep.RENDER, ProcessingOutcome.DONE, null, type, pageCount,
                renditions, false, true));
    }

    private RenditionBlob storeText(ProcessingStepPayload step, VersionToProcess version, String text) {
        return store(step, version, TEXT, "text/plain; charset=UTF-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private RenditionBlob storeThumbnail(ProcessingStepPayload step, VersionToProcess version, byte[] png) {
        return store(step, version, THUMBNAIL, "image/png", png);
    }

    private RenditionBlob store(ProcessingStepPayload step, VersionToProcess version, String kind, String mimeType,
            byte[] bytes) {
        StoredBlob blob = encryption.store(step.tenantId(), version.tenantCode(), new ByteArrayInputStream(bytes),
                Long.MAX_VALUE);
        return new RenditionBlob(kind, mimeType, blob.blobKey(), blob.sha256(), blob.sizeBytes(), blob.wrappedKey(),
                blob.keyRef());
    }

    private InputStream open(ProcessingStepPayload step, VersionToProcess version) {
        return encryption.open(step.tenantId(), version.content());
    }

    private byte[] readAll(ProcessingStepPayload step, VersionToProcess version) throws IOException {
        try (InputStream content = open(step, version)) {
            return content.readAllBytes();
        }
    }

    private NewJob next(JobType type, ProcessingStepPayload step, String detectedMimeType) {
        return new NewJob(step.tenantId(), type.name(), jsonMapper.writeValueAsString(new ProcessingStepPayload(
                step.tenantId(), step.documentId(), step.versionId(), detectedMimeType)));
    }

    private NewJob result(ProcessingStepPayload step, ProcessingStep which, ProcessingOutcome outcome, String detail,
            String detectedMimeType, Integer pageCount, List<RenditionBlob> renditions, boolean ocr, boolean last) {
        return new NewJob(step.tenantId(), JobType.PROCESSING_RESULT.name(), jsonMapper.writeValueAsString(
                new ProcessingResult(step.tenantId(), step.documentId(), step.versionId(), which, outcome, detail,
                        detectedMimeType, pageCount, renditions, ocr, last)));
    }

    /** SCAN jobs carry the core's {@link VersionJobPayload}; later steps carry {@link ProcessingStepPayload}. */
    private ProcessingStepPayload payload(ClaimedJob job) {
        ProcessingStepPayload step = jsonMapper.readValue(job.payload(), ProcessingStepPayload.class);
        if (!job.tenantId().equals(step.tenantId())) {
            throw new PermanentJobFailure("Job tenant and payload tenant differ");
        }
        return step;
    }

    static boolean meaningful(String text) {
        return text.codePoints().filter(Character::isLetterOrDigit).limit(MIN_TEXT).count() >= MIN_TEXT;
    }

    static String baseType(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        return (semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)).strip().toLowerCase();
    }
}
