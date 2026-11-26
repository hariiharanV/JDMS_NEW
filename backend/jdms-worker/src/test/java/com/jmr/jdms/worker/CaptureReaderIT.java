package com.jmr.jdms.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.contracts.CaptureOutcome;
import com.jmr.jdms.contracts.CaptureResult;
import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.RenditionBlob;
import com.jmr.jdms.platform.content.StoredBlob;
import com.jmr.jdms.platform.jobs.JobRunner;
import com.jmr.jdms.worker.support.TestFiles;
import com.jmr.jdms.worker.support.WorkerContainers;
import com.jmr.jdms.worker.support.WorkerFixtures;
import com.jmr.jdms.worker.support.WorkerFixtures.Capture;
import com.jmr.jdms.worker.support.WorkerFixtures.Field;
import com.jmr.jdms.worker.support.WorkerIntegrationTest;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-15 in the worker (ADR-0024) against real ClamAV, Tika with Tesseract, S3 and PostgreSQL: an attached file is
 * scanned, its text extracted or recognised, and the values found for the upload form are reported to the core in an
 * encrypted blob.
 */
@WorkerIntegrationTest
class CaptureReaderIT {

    private static final String[] FORM = {
            "SAMPLE BANK SAOG - Account Opening Form",
            "Customer Name: Ahmed Salim Al Balushi",
            "CIF No: C25112594",
            "Account Number: 0101 2345 6789",
            "Expiry Date: 31/12/2028",
            "Monthly Salary: OMR 1,250.500",
            "Currency: Omani Rial"};

    @Autowired WorkerFixtures fixtures;
    @Autowired JobRunner runner;
    @Autowired JsonMapper jsonMapper;
    @Autowired @Qualifier("gotenberg") GenericContainer<?> gotenberg;

    @Test
    void aScannedFormIsRecognisedAndItsFieldsSuggested() throws Exception {
        Capture capture = fixtures.capture(TestFiles.scannedForm(WorkerContainers.url(gotenberg), FORM),
                new Field("salary", "Monthly salary", "NUMBER", Map.of()),
                new Field("currency", "Currency", "LOV", Map.of("OMR", "Omani Rial", "USD", "US Dollar")));

        runner.runUntilIdle();

        List<CaptureResult> results = fixtures.results(capture);
        assertThat(results).hasSize(1);
        CaptureResult result = results.getFirst();
        assertThat(result.outcome()).isEqualTo(CaptureOutcome.DONE);
        assertThat(result.ocr()).isTrue();
        assertThat(result.detectedMimeType()).isEqualTo("image/png");
        Map<String, String> values = values(capture, result);
        assertThat(values).containsEntry("customerName", "Ahmed Salim Al Balushi")
                .containsEntry("cif", "C25112594")
                .containsEntry("accountNo", "010123456789")
                .containsEntry("expiryDate", "2028-12-31")
                .containsEntry("customFields.salary", "1250.500")
                .containsEntry("customFields.currency", "OMR");
        assertThat(result.suggestionCount()).isEqualTo(values.size());
        // the suggestions are a new blob of the tenant, encrypted; the attached file is only read
        assertThat(result.suggestions().blobKey()).startsWith(capture.tenantCode() + "/")
                .isNotEqualTo(capture.blob().blobKey());
        try (InputStream original = fixtures.open(capture.tenantId(), capture.blob())) {
            assertThat(original.readAllBytes()).startsWith(0x89, 'P', 'N', 'G');
        }
    }

    @Test
    void aDigitalPdfIsReadWithoutOcr() throws Exception {
        Capture capture = fixtures.capture(TestFiles.textPdf("Loan Agreement", "Contract Ref: LN/2026/00042",
                "Customer Name: ACME Trading LLC", "CIF: 778899"));

        runner.runUntilIdle();

        CaptureResult result = fixtures.results(capture).getFirst();
        assertThat(result.ocr()).isFalse();
        assertThat(values(capture, result)).containsEntry("contractRef", "LN/2026/00042")
                .containsEntry("customerName", "ACME Trading LLC").containsEntry("cif", "778899");
    }

    @Test
    void anInfectedFileSuggestsNothing() {
        Capture capture = fixtures.capture(TestFiles.eicar());

        runner.runUntilIdle();

        CaptureResult result = fixtures.results(capture).getFirst();
        assertThat(result.outcome()).isEqualTo(CaptureOutcome.INFECTED);
        assertThat(result.detail()).isEqualTo("Eicar-Test-Signature");
        assertThat(result.suggestions()).isNull();
    }

    @Test
    void aFileWithoutLabelsIsReadAndSuggestsNothing() throws Exception {
        Capture capture = fixtures.capture(TestFiles.textPdf("Minutes of the credit committee",
                "The committee approved the facility after discussion."));

        runner.runUntilIdle();

        CaptureResult result = fixtures.results(capture).getFirst();
        assertThat(result.outcome()).isEqualTo(CaptureOutcome.DONE);
        assertThat(result.suggestions()).isNull();
        assertThat(result.suggestionCount()).isZero();
    }

    @Test
    void aCaptureAlreadyReadIsNotReadAgain() throws Exception {
        Capture capture = fixtures.capture(TestFiles.textPdf("CIF: 778899"));
        runner.runUntilIdle();
        fixtures.markRead(capture);

        fixtures.readAgain(capture);
        runner.runUntilIdle();

        assertThat(fixtures.results(capture)).hasSize(1);
    }

    private Map<String, String> values(Capture capture, CaptureResult result) throws Exception {
        RenditionBlob blob = result.suggestions();
        try (InputStream in = fixtures.open(capture.tenantId(), new StoredBlob(blob.blobKey(), blob.sha256(),
                blob.sizeBytes(), blob.wrappedKey(), blob.keyRef()))) {
            List<FieldSuggestion> suggestions = jsonMapper.readValue(in.readAllBytes(),
                    new TypeReference<List<FieldSuggestion>>() {
                    });
            return suggestions.stream().collect(Collectors.toMap(FieldSuggestion::field, FieldSuggestion::value,
                    (a, b) -> a));
        }
    }
}
