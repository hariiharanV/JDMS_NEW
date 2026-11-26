package com.jmr.jdms.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmr.jdms.migration.config.MigrationProperties.Mapping;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyDocument;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyField;
import com.jmr.jdms.migration.legacy.LegacyRepository.LegacyStep;
import com.jmr.jdms.migration.report.Reconciliation.Outcome;
import com.jmr.jdms.migration.run.DocumentMapper.MappingException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentMapperTest {

    static final Mapping MAPPING = new Mapping(Map.of("docs/rcpt", "RECEIPT"), Map.of(), Map.of("R1", "CHECKER"),
            Map.of("V", List.of("VIEW_METADATA", "VIEW_CONTENT"), "D", List.of("DOWNLOAD")),
            Map.of("SUBMITTED", "SUBMIT", "APPROVED", "APPROVE", "RETURNED", "RETURN"),
            List.of("NEW", "WORKINPROGRESS"), "{name}.v{n}", "RESTRICTED");

    final DocumentMapper mapper = new DocumentMapper(MAPPING);

    @Test
    void slotsBecomeTypedCustomFields() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("cust_str_field1", "  ok ");
        slots.put("cust_num_field1", new BigDecimal("125.500"));
        slots.put("cust_dt_field1", Timestamp.valueOf("2021-06-01 00:00:00"));
        slots.put("cust_str_field2", "   ");
        List<LegacyField> fields = List.of(field("Note", "CUST_STR_FIELD1"), field("Amount", "cust_num_field1"),
                field("Issue Date", "cust_dt_field1"), field("Empty", "cust_str_field2"));

        Map<String, Object> custom = mapper.customFields(document("APPROVED", "ACTIVE", "R1", "VD", slots), fields);

        assertThat(custom).containsExactly(Map.entry("note", "ok"), Map.entry("amount", new BigDecimal("125.5")),
                Map.entry("issue_date", "2021-06-01"));
        assertThat(DocumentMapper.dataType("cust_dt_field3")).isEqualTo("DATE");
        assertThat(DocumentMapper.key("2nd Copy")).isEqualTo("f_2nd_copy");
    }

    @Test
    void historyStartsANewRoundAfterEachReturn() {
        List<Map<String, Object>> history = mapper.history(List.of(step("SUBMITTED"), step("RETURNED"),
                step("SUBMITTED"), step("APPROVED")));

        assertThat(history).extracting(entry -> entry.get("round")).containsExactly(1, 1, 2, 2);
        assertThat(history).extracting(entry -> entry.get("decision"))
                .containsExactly("SUBMIT", "RETURN", "SUBMIT", "APPROVE");
        assertThatThrownBy(() -> mapper.history(List.of(step("ESCALATED"))))
                .isInstanceOf(MappingException.class)
                .satisfies(e -> assertThat(((MappingException) e).outcome()).isEqualTo(Outcome.UNMAPPED));
    }

    @Test
    void permissionsMapRolesAndLettersAndStopOnAnythingUnknown() {
        assertThat(mapper.permissions(document("APPROVED", "ACTIVE", "R1", "VD", Map.of())))
                .singleElement().satisfies(rule -> {
                    assertThat(rule.get("role")).isEqualTo("CHECKER");
                    assertThat(rule.get("actions")).isEqualTo(List.of("VIEW_METADATA", "VIEW_CONTENT", "DOWNLOAD"));
                });
        assertThat(mapper.legacyActions(List.of("R1"), document("APPROVED", "ACTIVE", "R1", "V", Map.of())))
                .containsExactly("VIEW_METADATA", "VIEW_CONTENT");
        assertThat(mapper.legacyActions(List.of("R2"), document("APPROVED", "ACTIVE", "R1", "V", Map.of())))
                .isEmpty();
        assertThatThrownBy(() -> mapper.permissions(document("APPROVED", "ACTIVE", "R9", "V", Map.of())))
                .isInstanceOf(MappingException.class).hasMessageContaining("R9");
        assertThatThrownBy(() -> mapper.permissions(document("APPROVED", "ACTIVE", "R1", "Z", Map.of())))
                .isInstanceOf(MappingException.class).hasMessageContaining("'Z'");
    }

    @Test
    void documentsInApprovalOrRejectedAreNotMigrated() {
        assertThatThrownBy(() -> mapper.requireMigratable(document("WORKINPROGRESS", "ACTIVE", null, null, Map.of())))
                .isInstanceOf(MappingException.class)
                .satisfies(e -> assertThat(((MappingException) e).outcome()).isEqualTo(Outcome.IN_WORKFLOW));
        assertThatThrownBy(() -> mapper.requireMigratable(document("REJECTED", "ACTIVE", null, null, Map.of())))
                .isInstanceOf(MappingException.class);
        mapper.requireMigratable(document("APPROVED", "DELETED", null, null, Map.of()));
        assertThat(mapper.deleted(document("APPROVED", "DELETED", null, null, Map.of()))).isTrue();
        assertThat(mapper.typeCode(document("APPROVED", "ACTIVE", null, null, Map.of()))).isEqualTo("RECEIPT");
        assertThat(mapper.versionFileName("a.pdf", 2)).isEqualTo("a.pdf.v2");
    }

    @Test
    void knownExtensionsMustLookLikeTheirFormat() {
        assertThat(ContentSignatures.matches("pdf", "%PDF-1.7".getBytes())).isTrue();
        assertThat(ContentSignatures.matches("PDF", new byte[] {1, 2, 3, 4, 5})).isFalse();
        assertThat(ContentSignatures.matches("tif", new byte[] {'I', 'I', 0x2A, 0, 9})).isTrue();
        assertThat(ContentSignatures.matches("bin", new byte[] {1})).isTrue();
    }

    private static LegacyDocument document(String wfStatus, String status, String role, String permission,
            Map<String, Object> slots) {
        return new LegacyDocument("L1", "docs", "Receipt", "pdf", "rcpt", 1, LocalDate.of(2030, 1, 1),
                Instant.parse("2019-01-15T08:30:00Z"), "/Inbox", "r1.pdf", "001", role, permission, wfStatus, status,
                "C1", null, null, null, "ahmed", null, null, null, slots);
    }

    private static LegacyField field(String name, String slot) {
        return new LegacyField("docs", "rcpt", name, slot, name, false, 1, 50);
    }

    private static LegacyStep step(String action) {
        return new LegacyStep("Checker", action, "fatima", null, Instant.parse("2019-01-15T09:00:00Z"));
    }
}
