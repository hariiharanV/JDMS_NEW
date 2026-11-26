package com.jmr.jdms.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.jmr.jdms.contracts.ProcessingOutcome;
import com.jmr.jdms.contracts.ProcessingResult;
import com.jmr.jdms.contracts.ProcessingStep;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.worker.support.TestFiles;
import com.jmr.jdms.worker.support.WorkerContainers;
import com.jmr.jdms.worker.support.WorkerFixtures;
import com.jmr.jdms.worker.support.WorkerFixtures.Version;
import com.jmr.jdms.worker.support.WorkerIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.containers.GenericContainer;

/**
 * B-06 pipeline against real ClamAV, Tika (with Arabic OCR), Gotenberg, S3 and PostgreSQL: each step's result as
 * reported to the core, the renditions it stored, and the limits that keep untrusted files from hurting the worker.
 */
@WorkerIntegrationTest
class ProcessingPipelineIT {

    @Autowired WorkerFixtures fixtures;
    @Autowired JobRunner runner;
    @Autowired @Qualifier("gotenberg") GenericContainer<?> gotenberg;

    @Test
    void digitalPdfIsScannedExtractedAndThumbnailed() throws Exception {
        byte[] pdf = TestFiles.textPdf("Loan agreement between SAMPLE BANK and ACME LLC", "Facility amount 250000");
        Version version = fixtures.upload(pdf, "application/octet-stream"); // the claimed type does not matter

        runner.runUntilIdle();

        List<ProcessingResult> results = fixtures.results(version);
        assertThat(results).extracting(ProcessingResult::step, ProcessingResult::outcome).containsExactly(
                tuple(ProcessingStep.SCAN, ProcessingOutcome.CLEAN),
                tuple(ProcessingStep.EXTRACT, ProcessingOutcome.DONE),
                tuple(ProcessingStep.RENDER, ProcessingOutcome.DONE));
        assertThat(results).extracting(ProcessingResult::last).containsExactly(false, false, true);
        assertThat(results.get(1).detectedMimeType()).isEqualTo("application/pdf");
        assertThat(text(version, rendition(results.get(1), "TEXT"))).contains("Loan agreement between SAMPLE BANK");
        ProcessingResult render = results.get(2);
        assertThat(render.pageCount()).isEqualTo(2);
        assertThat(render.renditions()).extracting(RenditionBlob::kind).containsExactly("THUMBNAIL"); // PDF: no copy
        assertThat(thumbnail(version, rendition(render, "THUMBNAIL"))).satisfies(image ->
                assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(256));
        // the original is only read: its blob still decrypts to the uploaded bytes
        try (InputStream original = fixtures.open(version.tenantId(), version.blob())) {
            assertThat(original.readAllBytes()).isEqualTo(pdf);
        }
        assertThat(rendition(render, "THUMBNAIL").blobKey()).startsWith(version.tenantCode() + "/")
                .isNotEqualTo(version.blob().blobKey());
    }

    @Test
    void officeDocumentsGetAPdfRendition() throws Exception {
        Version version = fixtures.upload(TestFiles.docx("Board resolution approving the credit line"),
                "application/msword");

        runner.runUntilIdle();

        List<ProcessingResult> results = fixtures.results(version);
        assertThat(results.get(1).detectedMimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(text(version, rendition(results.get(1), "TEXT"))).contains("Board resolution");
        ProcessingResult render = results.getLast();
        assertThat(render.renditions()).extracting(RenditionBlob::kind).containsExactlyInAnyOrder("PDF", "THUMBNAIL");
        assertThat(render.pageCount()).isEqualTo(1);
        try (InputStream rendered = fixtures.open(version.tenantId(), blob(rendition(render, "PDF")))) {
            assertThat(new String(rendered.readNBytes(5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }
    }

    @Test
    void scannedImagesAreOcrInEnglishAndArabic() throws Exception {
        Version version = fixtures.upload(TestFiles.scannedPng(WorkerContainers.url(gotenberg)), "image/png");

        runner.runUntilIdle();

        List<ProcessingResult> results = fixtures.results(version);
        assertThat(results).extracting(ProcessingResult::step).containsExactly(ProcessingStep.SCAN,
                ProcessingStep.EXTRACT, ProcessingStep.OCR, ProcessingStep.RENDER);
        assertThat(results.get(1).renditions()).isEmpty(); // no text layer
        ProcessingResult ocr = results.get(2);
        assertThat(ocr.ocr()).isTrue();
        String text = text(version, rendition(ocr, "TEXT"));
        assertThat(text).contains("Account statement").contains("كشف حساب");
        assertThat(results.get(3).renditions()).extracting(RenditionBlob::kind).containsExactly("THUMBNAIL");
    }

    @Test
    void imageOnlyPdfsAreOcr() throws Exception {
        byte[] scan = TestFiles.scannedPdf(TestFiles.scannedPng(WorkerContainers.url(gotenberg)));
        Version version = fixtures.upload(scan, "application/pdf");

        runner.runUntilIdle();

        ProcessingResult ocr = fixtures.results(version).get(2);
        assertThat(ocr.step()).isEqualTo(ProcessingStep.OCR);
        assertThat(text(version, rendition(ocr, "TEXT"))).contains("Account statement").contains("التجريبي");
    }

    @Test
    void eicarIsReportedInfectedAndProcessingStops() {
        Version version = fixtures.upload(TestFiles.eicar(), "application/pdf");

        runner.runUntilIdle();

        List<ProcessingResult> results = fixtures.results(version);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(ProcessingOutcome.INFECTED);
        assertThat(results.getFirst().detail()).isEqualTo("Eicar-Test-Signature");
        assertThat(results.getFirst().last()).isTrue();
        assertThat(fixtures.jobs(version, "EXTRACT")).isEmpty();
    }

    @Test
    void malformedFilesFailWithoutRetries() {
        Version version = fixtures.upload(TestFiles.malformedPdf(), "application/pdf");

        runner.runUntilIdle();

        ProcessingResult last = fixtures.results(version).getLast();
        assertThat(last.outcome()).isEqualTo(ProcessingOutcome.FAILED);
        assertThat(last.last()).isTrue();
        assertThat(last.detail()).contains("HTTP 422");
        assertThat(fixtures.jobs(version, last.step().name())).singleElement()
                .satisfies(job -> assertThat(job).containsEntry("status", "FAILED").containsEntry("attempts", 1));
    }

    @Test
    void filesOverTheProcessingLimitAreNotProcessed() {
        Version version = fixtures.upload(TestFiles.random(6 * 1024 * 1024), "application/pdf"); // limit: 5 MB

        runner.runUntilIdle();

        ProcessingResult only = fixtures.results(version).getFirst();
        assertThat(only.step()).isEqualTo(ProcessingStep.SCAN);
        assertThat(only.outcome()).isEqualTo(ProcessingOutcome.FAILED);
        assertThat(only.detail()).contains("larger than the processing limit");
    }

    @Test
    void aZipBombDoesNotHurtTheWorker() throws Exception {
        Version bomb = fixtures.upload(TestFiles.zipBomb(400), "application/zip");

        runner.runUntilIdle();

        // the pipeline ends (the expanded text is capped), and the worker keeps processing other files
        assertThat(fixtures.results(bomb).getLast().last()).isTrue();
        Version next = fixtures.upload(TestFiles.textPdf("Still working after the zip bomb"), "application/pdf");
        runner.runUntilIdle();
        assertThat(fixtures.results(next).getLast().outcome()).isEqualTo(ProcessingOutcome.DONE);
    }

    @Test
    void duplicateDeliveryAddsNoRenditions() throws Exception {
        Version version = fixtures.upload(TestFiles.docx("Duplicate delivery"), "application/octet-stream");
        runner.runUntilIdle();
        List<ProcessingResult> first = fixtures.results(version);
        // the core applied the results: renditions recorded, processing done
        first.forEach(result -> fixtures.applyRenditions(version, result, null));

        fixtures.scanAgain(version); // e.g. a lease expired while the first run was finishing
        runner.runUntilIdle();

        List<ProcessingResult> all = fixtures.results(version);
        assertThat(all.size()).isGreaterThan(first.size()); // the steps ran again...
        assertThat(all.subList(first.size(), all.size())).allSatisfy(result ->
                assertThat(result.renditions()).isEmpty()); // ...but stored nothing new

        fixtures.applyRenditions(version, new ProcessingResult(version.tenantId(), version.documentId(),
                version.versionId(), ProcessingStep.RENDER, ProcessingOutcome.DONE, null, null, null, List.of(), false,
                true), "DONE");
        int before = fixtures.results(version).size();
        fixtures.scanAgain(version);
        runner.runUntilIdle();
        assertThat(fixtures.results(version)).hasSize(before); // a completed version is not scanned again
    }

    private static RenditionBlob rendition(ProcessingResult result, String kind) {
        return result.renditions().stream().filter(rendition -> rendition.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " rendition in " + result));
    }

    private static StoredBlob blob(RenditionBlob rendition) {
        return new StoredBlob(rendition.blobKey(), rendition.sha256(), rendition.sizeBytes(), rendition.wrappedKey(),
                rendition.keyRef());
    }

    private String text(Version version, RenditionBlob rendition) throws Exception {
        try (InputStream in = fixtures.open(version.tenantId(), blob(rendition))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private BufferedImage thumbnail(Version version, RenditionBlob rendition) throws Exception {
        assertThat(rendition.mimeType()).isEqualTo("image/png");
        try (InputStream in = fixtures.open(version.tenantId(), blob(rendition))) {
            return ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        }
    }
}
