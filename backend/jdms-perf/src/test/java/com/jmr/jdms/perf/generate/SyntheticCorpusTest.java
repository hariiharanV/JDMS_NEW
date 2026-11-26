package com.jmr.jdms.perf.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.perf.generate.SyntheticCorpus.SyntheticDocument;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SyntheticCorpusTest {

    final SyntheticCorpus corpus = new SyntheticCorpus(7, 1000, 0.3);
    final List<SyntheticDocument> sample = LongStream.range(0, 5000).mapToObj(corpus::document).toList();

    /** Required standard fields and custom fields per type, as in the PERF catalog (perf/perf-tenant.sql). */
    static final Map<String, Set<String>> REQUIRED_CUSTOM = Map.of(
            "PASSPORT", Set.of("passport_no", "nationality", "issue_date"),
            "NATIONAL_ID", Set.of("national_id_no", "issue_date"),
            "ACCOUNT_OPENING_FORM", Set.of("account_type", "date_opened"),
            "LOAN_AGREEMENT", Set.of("facility_type", "amount", "currency", "loan_origination_date"),
            "COLLATERAL_DEED", Set.of("collateral_type", "valuation_amount", "valuation_date"),
            "SALARY_CERTIFICATE", Set.of("employer", "monthly_salary", "issue_date"));

    @Test
    void theSameSeedAndIndexGiveTheSameDocument() {
        assertThat(new SyntheticCorpus(7, 1000, 0.3).document(4242)).isEqualTo(corpus.document(4242));
        assertThat(new SyntheticCorpus(8, 1000, 0.3).document(4242)).isNotEqualTo(corpus.document(4242));
    }

    @Test
    void documentsFitTheCatalog() {
        assertThat(sample).allSatisfy(document -> {
            assertThat(document.customFields()).containsKeys(REQUIRED_CUSTOM.get(document.typeCode())
                    .toArray(String[]::new));
            assertThat(document.cif()).matches("C\\d{8}");
            assertThat(document.branchCode()).matches("0(0[1-9]|1\\d|20)");
            assertThat(document.title().length()).isLessThanOrEqualTo(500);
            switch (document.typeCode()) {
                case "PASSPORT", "NATIONAL_ID" -> assertThat(document.expiryDate()).isNotNull();
                case "ACCOUNT_OPENING_FORM" -> assertThat(document.accountNo()).isNotNull();
                case "LOAN_AGREEMENT" -> assertThat(document.contractRef()).isNotNull();
                case "COLLATERAL_DEED" -> assertThat(document.collateralCode()).isNotNull();
                default -> { }
            }
            assertThat(document.cabinetCode()).isEqualTo(Set.of("PASSPORT", "NATIONAL_ID", "ACCOUNT_OPENING_FORM")
                    .contains(document.typeCode()) ? "CUSTOMERS" : "LOANS");
        });
    }

    @Test
    void theMixLooksLikeABank() {
        Map<String, Long> types = count(SyntheticDocument::typeCode);
        assertThat(types).hasSize(6);
        assertThat(share(count(SyntheticDocument::language).get("ar"))).isBetween(0.25, 0.35);
        assertThat(share(count(SyntheticDocument::branchCode).get("001"))).isBetween(0.15, 0.30); // head office
        assertThat(share(sample.stream().filter(SyntheticDocument::deleted).count())).isBetween(0.01, 0.03);
        assertThat(sample.stream().map(SyntheticDocument::cif).distinct().count()).isLessThan(1001);
        assertThat(sample).allSatisfy(document -> assertThat(document.text().length()).isBetween(300, 6000));
        assertThat(sample.stream().filter(document -> document.language().equals("ar")).findFirst().orElseThrow()
                .text()).containsPattern("\\p{IsArabic}");
    }

    private Map<String, Long> count(Function<SyntheticDocument, String> key) {
        return sample.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    private double share(long count) {
        return (double) count / sample.size();
    }
}
