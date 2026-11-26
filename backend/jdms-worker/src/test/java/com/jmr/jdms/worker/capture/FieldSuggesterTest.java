package com.jmr.jdms.worker.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.contracts.FieldSuggestion;
import com.jmr.jdms.contracts.FieldSuggestion.Confidence;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** B-15: values read from English document text for the upload form (ADR-0024). */
class FieldSuggesterTest {

    private static final FieldSuggester STANDARD = new FieldSuggester(List.of());

    @Test
    void readsLabelledStandardFieldsFromAStatement() {
        String text = """
                SAMPLE BANK SAOG
                Account Statement

                Customer Name: Ahmed Al Balushi
                CIF No.: C25112594
                Account No.: 0101 2345 6789 01
                Statement period 01/10/2026 to 31/10/2026
                """;

        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(text));

        assertThat(found.keySet()).containsExactly("cif", "customerName", "accountNo");
        assertThat(found.get("customerName").value()).isEqualTo("Ahmed Al Balushi");
        assertThat(found.get("cif").value()).isEqualTo("C25112594");
        assertThat(found.get("accountNo").value()).isEqualTo("01012345678901");
        assertThat(found.values()).extracting(FieldSuggestion::confidence).containsOnly(Confidence.HIGH);
        assertThat(found.get("cif").label()).isEqualTo("CIF No");
    }

    @Test
    void readsSeveralLabelsOnOneLineAndStopsAtColumnGaps() {
        String text = "Customer Name: JOHN SMITH    CIF: 778899    Contract Ref: LN/2026/00042\n"
                + "Application No - APP-77812   Collateral Code # COL-5531\n";

        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(text));

        assertThat(found.get("customerName").value()).isEqualTo("JOHN SMITH");
        assertThat(found.get("cif").value()).isEqualTo("778899");
        assertThat(found.get("contractRef").value()).isEqualTo("LN/2026/00042");
        assertThat(found.get("applicationRef").value()).isEqualTo("APP-77812");
        assertThat(found.get("collateralCode").value()).isEqualTo("COL-5531");
    }

    @Test
    void labelsNextToEachOtherWithoutAGapStillSplit() {
        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(
                "CIF: C100200 Account Number: 5566778899 Customer Name: Sara Ali"));

        assertThat(found.get("cif").value()).isEqualTo("C100200");
        assertThat(found.get("accountNo").value()).isEqualTo("5566778899");
        assertThat(found.get("customerName").value()).isEqualTo("Sara Ali");
    }

    @Test
    void aValueOnTheNextLineIsMediumConfidence() {
        String text = """
                Account Holder
                Fatma Said Al Hinai

                CIF
                C9988776
                """;

        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(text));

        assertThat(found.get("customerName").value()).isEqualTo("Fatma Said Al Hinai");
        assertThat(found.get("customerName").confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(found.get("cif").value()).isEqualTo("C9988776");
        assertThat(found.get("cif").confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void labelWordsInRunningTextAreIgnored() {
        String text = """
                Please credit the account of the customer named below with the amount stated.
                The customer number will be confirmed by the branch 2026.
                """;

        assertThat(STANDARD.suggest(text)).isEmpty();
    }

    @Test
    void aLabelWithoutAValueOfTheRightFormSuggestsNothing() {
        String text = """
                CIF: to be assigned
                Account No: N/A
                Expiry Date: none
                Customer Name: 12345
                """;

        assertThat(STANDARD.suggest(text)).isEmpty();
    }

    @Test
    void genericLabelsAndConflictsAreMediumConfidence() {
        String text = """
                Name: Khalid Rashid
                Ref No: TRX-1001
                CIF: C111111
                CIF: C222222
                """;

        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(text));

        assertThat(found.get("customerName").confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(found.get("transactionRef").value()).isEqualTo("TRX-1001");
        assertThat(found.get("transactionRef").confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(found.get("cif").value()).isEqualTo("C111111"); // the first one
        assertThat(found.get("cif").confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void aSpecificLabelBeatsAGenericOne() {
        String text = """
                Name: Branch Operations
                Customer Name: Mariam Al Lawati
                """;

        FieldSuggestion name = byField(STANDARD.suggest(text)).get("customerName");

        assertThat(name.value()).isEqualTo("Mariam Al Lawati");
        assertThat(name.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void readsDatesInTheUsualWritings() {
        assertThat(expiry("Expiry Date: 31/12/2027")).isEqualTo("2027-12-31");
        assertThat(expiry("Expiry Date: 05/04/2027")).isEqualTo("2027-04-05"); // day first
        assertThat(expiry("Expiry Date: 04/25/2027")).isEqualTo("2027-04-25"); // cannot be day first
        assertThat(expiry("Date of Expiry: 2027-03-09")).isEqualTo("2027-03-09");
        assertThat(expiry("Valid Until: 9 March 2028")).isEqualTo("2028-03-09");
        assertThat(expiry("Expires On: Mar 9, 2028")).isEqualTo("2028-03-09");
        assertThat(expiry("Expiry: 01.02.29")).isEqualTo("2029-02-01");
        assertThat(expiry("Expiry Date: 31/02/2027")).isNull(); // no such day
    }

    @Test
    void readsAnIbanAsTheAccount() {
        assertThat(byField(STANDARD.suggest("IBAN: OM81 0180 0000 0299 9012 345")).get("accountNo").value())
                .isEqualTo("OM810180000002999012345");
    }

    @Test
    void readsCustomFieldsByTheirLabelsAndTypes() {
        FieldSuggester suggester = new FieldSuggester(List.of(
                new FieldHint("loan_amount", "Loan amount", "NUMBER", null, null),
                new FieldHint("currency", "Currency", "LOV", null, Map.of("OMR", "Omani Rial", "USD", "US Dollar")),
                new FieldHint("maturity_date", "Maturity date", "DATE", null, null),
                new FieldHint("secured", "Secured", "BOOLEAN", null, null),
                new FieldHint("purpose", "Purpose of loan", "TEXT", 40, null),
                new FieldHint("employer", "Employer", "TEXT", 10, null)));
        String text = """
                Loan Amount: OMR 25,000.500
                Currency: Omani Rial
                Maturity Date: 15/06/2031
                Secured: Yes
                Purpose of loan: Home renovation
                Employer: Ministry of Housing and Urban Planning
                """;

        Map<String, FieldSuggestion> found = byField(suggester.suggest(text));

        assertThat(found.get("customFields.loan_amount").value()).isEqualTo("25000.500");
        assertThat(found.get("customFields.currency").value()).isEqualTo("OMR");
        assertThat(found.get("customFields.maturity_date").value()).isEqualTo("2031-06-15");
        assertThat(found.get("customFields.secured").value()).isEqualTo("true");
        assertThat(found.get("customFields.purpose").value()).isEqualTo("Home renovation");
        assertThat(found).doesNotContainKey("customFields.employer"); // longer than the field allows
    }

    @Test
    void aCustomFieldIsAlsoFoundByItsKey() {
        FieldSuggester suggester = new FieldSuggester(List.of(
                new FieldHint("passport_no", "Travel document", "TEXT", 20, null)));

        assertThat(byField(suggester.suggest("Passport No: P1234567")).get("customFields.passport_no").value())
                .isEqualTo("P1234567");
    }

    @Test
    void aListValueThatIsNotInTheListIsNotSuggested() {
        FieldSuggester suggester = new FieldSuggester(List.of(
                new FieldHint("currency", "Currency", "LOV", null, Map.of("OMR", "Omani Rial"))));

        assertThat(suggester.suggest("Currency: EUR")).isEmpty();
    }

    @Test
    void labelsAreMatchedRegardlessOfCaseAndSpacing() {
        Map<String, FieldSuggestion> found = byField(STANDARD.suggest(
                "customer   name : omar khalfan\nA/C No.:  0012-345678-001\n"));

        assertThat(found.get("customerName").value()).isEqualTo("omar khalfan");
        assertThat(found.get("accountNo").value()).isEqualTo("0012-345678-001");
    }

    @Test
    void largeInputIsBounded() {
        String filler = "x".repeat(FieldSuggester.MAX_TEXT_CHARS);
        assertThat(STANDARD.suggest(filler + "\nCIF: C123456\n")).isEmpty(); // beyond the limit: not read
        assertThat(STANDARD.suggest("CIF: C123456 " + "y".repeat(50_000))).extracting(FieldSuggestion::value)
                .containsExactly("C123456"); // a long line is cut, the start still read
    }

    @Test
    void aBilingualPassportPageIsReadFromItsMachineReadableZoneAndItsLabels() {
        // the layout of a scanned passport: "other language / English" labels with values below, OCR order jumbled
        FieldSuggester suggester = new FieldSuggester(List.of(
                new FieldHint("passport_no", "Passport number", "TEXT", null, null),
                new FieldHint("nationality", "Nationality", "LOV", null, Map.of("GB", "United Kingdom", "IN", "India")),
                new FieldHint("issue_date", "Issue date", "DATE", null, null),
                new FieldHint("place_of_issue", "Place of issue", "TEXT", null, null)));
        String text = """
                REPUBLIC OF UTOPIA
                xxxx / Surname
                ERIKSSON
                yyyy / Place of Issue
                15/04/2002
                zzzz / Date of Issue
                16/04/2002
                P<GBRERIKSSON<<ANNA<MARIA<<<<<< <<< KK KKK KKK
                L898902C361GBR7408 122F1204 159ZE184226B<<<<cc<h
                wwww 4./ Passport No.
                L898902C3
                """;

        Map<String, FieldSuggestion> found = byField(suggester.suggest(text));

        assertThat(found.get("customerName").value()).isEqualTo("ANNA MARIA ERIKSSON");
        assertThat(found.get("customerName").label()).isEqualTo("Machine-readable zone");
        assertThat(found.get("expiryDate").value()).isEqualTo("2012-04-15");
        assertThat(found.get("customFields.passport_no").value()).isEqualTo("L898902C3");
        assertThat(found.get("customFields.nationality").value()).isEqualTo("GB");
        assertThat(found.values()).filteredOn(s -> !s.field().equals("customFields.issue_date"))
                .extracting(FieldSuggestion::confidence).containsOnly(Confidence.HIGH);
        // "Date of Issue" fills the field labelled "Issue date"; a date is never a place
        assertThat(found.get("customFields.issue_date").value()).isEqualTo("2002-04-16");
        assertThat(found).doesNotContainKey("customFields.place_of_issue");
    }

    @Test
    void documentLabelsThatNameNoFieldAreNeverTakenAsValues() {
        FieldSuggester suggester = new FieldSuggester(List.of(
                new FieldHint("passport_no", "Passport number", "TEXT", null, null)));
        String text = """
                Passport No.
                Surname
                AL RAWAHI
                """;

        assertThat(suggester.suggest(text)).isEmpty();
        assertThat(byField(suggester.suggest("Passport No.\nX4218637\n")).get("customFields.passport_no").value())
                .isEqualTo("X4218637");
    }

    @Test
    void aLabelAfterTheOtherLanguagesLabelCountsAsALineStart() {
        Map<String, FieldSuggestion> found = byField(STANDARD.suggest("""
                abcd / Customer Name
                Fatma Al Hinai
                """));

        assertThat(found.get("customerName").value()).isEqualTo("Fatma Al Hinai");
    }

    private static String expiry(String line) {
        FieldSuggestion suggestion = byField(STANDARD.suggest(line)).get("expiryDate");
        return suggestion == null ? null : suggestion.value();
    }

    private static Map<String, FieldSuggestion> byField(List<FieldSuggestion> suggestions) {
        return suggestions.stream().collect(Collectors.toMap(FieldSuggestion::field, Function.identity(),
                (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
