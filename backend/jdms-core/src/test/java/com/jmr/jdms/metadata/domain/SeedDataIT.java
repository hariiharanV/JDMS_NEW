package com.jmr.jdms.metadata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmr.jdms.TestcontainersConfiguration;
import com.jmr.jdms.metadata.api.CustomFieldValidation;
import com.jmr.jdms.metadata.api.CustomFieldsResult;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.tenant.api.TenantDirectory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B-02 seed data: the local seed (Flyway {@code db/seed/postgresql/local}, as loaded by the {@code local} profile)
 * creates tenant BANK1 with cabinets CUSTOMERS and LOANS and the six document types with realistic fields, and the
 * validation engine accepts realistic documents for each type.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration/{vendor},classpath:db/seed/{vendor}/local")
@Import(TestcontainersConfiguration.class)
class SeedDataIT {

    @Autowired TenantDirectory tenants;
    @Autowired TransactionTemplate transactions;
    @Autowired CabinetRepository cabinets;
    @Autowired DocumentTypeRepository documentTypes;
    @Autowired DocumentTypeAdministration documentTypeAdministration;
    @Autowired CustomFieldValidation validation;

    UUID bank1;

    @BeforeEach
    void bank1() {
        bank1 = tenants.findActiveByCode("BANK1").orElseThrow().id();
    }

    @Test
    void cabinetsAndDocumentTypesAreSeeded() {
        inBank1(() -> {
            assertThat(cabinets.findAll()).extracting(Cabinet::getCode).containsExactlyInAnyOrder("CUSTOMERS", "LOANS");
            Map<String, String> typeCabinets = documentTypes.findAll().stream().collect(java.util.stream.Collectors
                    .toMap(DocumentType::getCode, type -> cabinets.findById(type.getCabinetId()).orElseThrow()
                            .getCode()));
            assertThat(typeCabinets).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "PASSPORT", "CUSTOMERS", "NATIONAL_ID", "CUSTOMERS", "ACCOUNT_OPENING_FORM", "CUSTOMERS",
                    "LOAN_AGREEMENT", "LOANS", "COLLATERAL_DEED", "LOANS", "SALARY_CERTIFICATE", "LOANS"));
            DocumentType salary = documentTypes.findByCode("SALARY_CERTIFICATE").orElseThrow();
            assertThat(salary.getDefaultClassification()).isEqualTo(Classification.RESTRICTED);
            assertThat(documentTypes.findByCode("PASSPORT").orElseThrow().getRequiredStandardFields())
                    .containsExactly("cif", "expiry_date");
            return null;
        });
    }

    @Test
    void everySeededFieldListPassesTheAdminRules() {
        inBank1(() -> {
            for (DocumentType type : documentTypes.findAll()) {
                List<FieldSpec> fields = documentTypeAdministration.fieldsOf(type.getId());
                assertThat(fields).as(type.getCode()).isNotEmpty();
                List<Violation> violations = FieldDefinitionRules.check(fields,
                        documentTypeAdministration.lovLookup(fields));
                assertThat(violations).as("seeded fields of %s", type.getCode()).isEmpty();
            }
            return null;
        });
    }

    @Test
    void realisticDocumentsValidateForEachType() {
        Map<String, Map<String, Object>> samples = Map.of(
                "PASSPORT", Map.of("passport_no", "A1234567", "nationality", "OM", "issue_date", "2022-03-01"),
                "NATIONAL_ID", Map.of("national_id_no", "12345678", "issue_date", "2021-07-15"),
                "ACCOUNT_OPENING_FORM", Map.of("account_type", "SAVINGS", "date_opened", "2026-10-01",
                        "officer", "Sara Al Balushi"),
                "LOAN_AGREEMENT", Map.of("facility_type", "HOME_LOAN", "amount", "85000.000",
                        "loan_origination_date", "2026-09-30", "tenor_months", 240),
                "COLLATERAL_DEED", Map.of("collateral_type", "PROPERTY", "valuation_amount", 120000,
                        "valuation_date", "2026-09-15"),
                "SALARY_CERTIFICATE", Map.of("employer", "Ministry of Health", "monthly_salary", "1450.500",
                        "issue_date", "2026-10-20"));

        samples.forEach((code, sample) -> {
            CustomFieldsResult result = validate(code, sample);
            assertThat(result.violations()).as(code).isEmpty();
        });

        CustomFieldsResult loan = validate("LOAN_AGREEMENT", samples.get("LOAN_AGREEMENT"));
        assertThat(loan.values()).containsEntry("currency", "OMR"); // default
        assertThat(loan.values()).containsEntry("amount", new BigDecimal("85000.000"));
        assertThat(validate("ACCOUNT_OPENING_FORM", samples.get("ACCOUNT_OPENING_FORM")).values())
                .containsEntry("joint_account", false);
    }

    @Test
    void invalidDocumentsAreRejectedWithFieldLevelReasons() {
        CustomFieldsResult result = validate("PASSPORT", Map.of("passport_no", "A12345678901234567890",
                "nationality", "XX", "issue_date", "01/03/2022", "eye_colour", "brown"));

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).extracting(v -> v.field() + ":" + v.code()).containsExactlyInAnyOrder(
                "passport_no:FIELD_TOO_LONG", "nationality:FIELD_LOV_VALUE", "issue_date:FIELD_TYPE",
                "eye_colour:FIELD_UNKNOWN");
        assertThat(validate("SALARY_CERTIFICATE", Map.of()).violations()).extracting(Violation::field)
                .containsExactlyInAnyOrder("employer", "monthly_salary", "issue_date");
    }

    private CustomFieldsResult validate(String typeCode, Map<String, Object> values) {
        return inBank1(() -> validation.validate(documentTypes.findByCode(typeCode).orElseThrow().getId(), values));
    }

    private <T> T inBank1(Supplier<T> work) {
        return TenantScope.call(bank1, () -> transactions.execute(status -> work.get()));
    }
}
