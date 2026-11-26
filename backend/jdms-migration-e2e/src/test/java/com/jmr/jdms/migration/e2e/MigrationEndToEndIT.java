package com.jmr.jdms.migration.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jmr.jdms.JdmsCoreApplication;
import com.jmr.jdms.TestcontainersConfiguration;
import com.jmr.jdms.migration.config.MigrationProperties;
import com.jmr.jdms.migration.config.MigrationProperties.Mapping;
import com.jmr.jdms.migration.legacy.LegacyFileCipher;
import com.jmr.jdms.migration.legacy.LegacyFileStore;
import com.jmr.jdms.migration.legacy.LegacyRepository;
import com.jmr.jdms.migration.report.Reconciliation;
import com.jmr.jdms.migration.report.Reconciliation.DocumentResult;
import com.jmr.jdms.migration.report.Reconciliation.Outcome;
import com.jmr.jdms.migration.report.Reconciliation.PermissionCheck;
import com.jmr.jdms.migration.run.CatalogProposer;
import com.jmr.jdms.migration.run.MigrationRunner;
import com.jmr.jdms.migration.client.CoreClient;
import com.jmr.jdms.support.TestJwts;
import com.jmr.jdms.support.TestMetadata;
import com.jmr.jdms.support.TestSecurityConfiguration;
import com.jmr.jdms.support.TestTenants;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B-12 end to end (ADR-0020): a legacy JDMS database (PostgreSQL stand-in for Oracle) and an AES-encrypted legacy
 * file tree are migrated by the tool into the real core, running in-process with its own test fixture. Proves the
 * mapping, decryption and re-encryption with checksums, the catalog step through maker-checker, one outcome per legacy
 * document with every exception listed, the permission comparison, idempotent re-runs, delta mode, and that no key
 * material reaches the logs.
 */
@SpringBootTest(classes = JdmsCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "jdms.integration.rate-limit.requests-per-second=1000",
                "jdms.integration.rate-limit.burst=1000"})
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, TestTenants.class,
        TestMetadata.class})
@ContextConfiguration(initializers = MigrationEndToEndIT.CoreOnly.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationEndToEndIT {

    /** The core scans {@code com.jmr.jdms}: keep the tool's own classes (on this classpath) out of its context. */
    static class CoreOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton("migrationToolExcludeFilter", new TypeExcludeFilter() {
                @Override
                public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
                    return reader.getClassMetadata().getClassName().startsWith("com.jmr.jdms.migration.");
                }

                @Override
                public boolean equals(Object other) {
                    return other != null && other.getClass() == getClass();
                }

                @Override
                public int hashCode() {
                    return getClass().hashCode();
                }
            });
        }
    }

    static final String SECRET = "legacy-e2e-" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
    static final Mapping MAPPING = new Mapping(Map.of(), Map.of(),
            Map.of("R_AUD", "LEGACY_AUDITOR", "R_MAK", "MAKER"),
            Map.of("V", List.of("VIEW_METADATA", "VIEW_CONTENT"), "D", List.of("DOWNLOAD")),
            Map.of("SUBMITTED", "SUBMIT", "APPROVED", "APPROVE", "RETURNED", "RETURN"),
            List.of("NEW", "WORKINPROGRESS"), "{name}.v{n}", "RESTRICTED");

    @Autowired TestTenants testTenants;
    @Autowired TestMetadata testMetadata;
    @Autowired JdbcTemplate coreJdbc;
    @LocalServerPort int port;
    @TempDir static Path work;

    PostgreSQLContainer legacyDb;
    JdbcTemplate legacyJdbc;
    CreatedTenant tenant;
    Path files;
    Path reports;
    CoreClient core;
    LegacyRepository legacy;
    LegacyFileStore store;
    LegacyFileCipher cipher;
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Map<String, byte[]> plain = new java.util.HashMap<>();
    Reconciliation firstRun;

    @BeforeAll
    void setUp() throws Exception {
        legacyDb = new PostgreSQLContainer(DockerImageName.parse(System.getProperty("test.image.postgres"))
                .asCompatibleSubstituteFor("postgres"));
        legacyDb.start();
        legacyJdbc = new JdbcTemplate(new DriverManagerDataSource(legacyDb.getJdbcUrl(), legacyDb.getUsername(),
                legacyDb.getPassword()));
        try (var connection = legacyJdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("legacy/schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("legacy/data.sql"));
        }

        files = Files.createDirectories(work.resolve("legacy-files"));
        writeLegacyFile("docs/versions/Inbox/r1.pdf.v1", pdf("receipt 1, first scan"));
        writeLegacyFile("docs/base/Inbox/r1.pdf", pdf("receipt 1, rescanned"));
        writeLegacyFile("docs/deleted/Inbox/r2.pdf", pdf("receipt 2"));
        byte[] damaged = encrypt(pdf("receipt 4"));
        Files.write(files.resolve("docs/base/damaged.pdf"), Arrays.copyOf(damaged, damaged.length - 5));
        writeLegacyFile("docs/base/garbage.pdf", "not a pdf at all".getBytes(StandardCharsets.UTF_8));
        writeLegacyFile("docs/base/r6.pdf", pdf("receipt 6"));
        writeLegacyFile("docs/base/r7.pdf", pdf("odd 7"));
        writeLegacyFile("docs/base/r8.pdf", pdf("receipt 8"));
        writeLegacyFile("docs/base/k9.pdf", pdf("kyc letter 9"));
        writeLegacyFile("docs/base/r10.pdf", pdf("receipt 10"));
        writeLegacyFile("docs/base/r11.pdf", pdf("receipt 1, rescanned")); // same content as L001's current file
        Path secretFile = work.resolve("legacy.secret");
        Files.writeString(secretFile, SECRET);
        reports = work.resolve("reports");

        tenant = testTenants.create("001", "002");
        testMetadata.create(tenant);
        String token = TestJwts.client(tenant.code(), "jdms-migration")
                .roles("MIGRATION", "TENANT_ADMIN", "SECURITY_ADMIN").branchScope("ALL").clearance("RESTRICTED").sign();
        core = new CoreClient(RestClient.builder(), URI.create("http://localhost:" + port), () -> token,
                JsonMapper.shared());
        legacy = new LegacyRepository(legacyJdbc);
        store = new LegacyFileStore(files, 50L * 1024 * 1024);
        cipher = LegacyFileCipher.fromSecretFile(secretFile);

        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterAll
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        legacyDb.stop();
    }

    @Test
    @Order(1)
    void theCatalogIsProposedAndBecomesRealOnlyAfterApproval() {
        Reconciliation catalog = new CatalogProposer(legacy, core, MAPPING).run();
        assertThat(catalog.catalogLines()).anyMatch(line -> line.startsWith("Document type RECEIPT: exists"))
                .anyMatch(line -> line.startsWith("Document type KYC_LETTER: proposed"))
                .anyMatch(line -> line.startsWith("Folder /Archive: proposed"));
        assertThat(typeCodes()).doesNotContain("KYC_LETTER");

        approvePending("TENANT_ADMIN");
        assertThat(typeCodes()).contains("KYC_LETTER");

        // the second pass proposes the permission of the now existing folder; a security admin approves it
        Reconciliation second = new CatalogProposer(legacy, core, MAPPING).run();
        assertThat(second.catalogLines()).anyMatch(line -> line.startsWith("Document type KYC_LETTER: exists"))
                .anyMatch(line -> line.startsWith("Permission of LEGACY_AUDITOR on folder /Archive: proposed"));
        approvePending("SECURITY_ADMIN");
        assertThat(coreJdbc.queryForObject("select count(*) from access_rule where tenant_id = ? and scope_type = "
                + "'FOLDER' and principal_id = 'LEGACY_AUDITOR'", Integer.class, tenant.id())).isEqualTo(1);
    }

    @Test
    @Order(2)
    void everyLegacyDocumentEndsInExactlyOneOutcome() throws Exception {
        firstRun = runner(null).run();
        Map<String, DocumentResult> results = firstRun.documents().stream()
                .collect(Collectors.toMap(DocumentResult::legacyDocumentId, Function.identity()));

        assertThat(results).hasSize(11);
        assertThat(outcomes(firstRun)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("L001", Outcome.MIGRATED), Map.entry("L002", Outcome.MIGRATED),
                Map.entry("L003", Outcome.MISSING_FILE), Map.entry("L004", Outcome.UNDECRYPTABLE),
                Map.entry("L005", Outcome.CORRUPT), Map.entry("L006", Outcome.IN_WORKFLOW),
                Map.entry("L007", Outcome.TYPE_NOT_MIGRATED), Map.entry("L008", Outcome.INVALID_METADATA),
                Map.entry("L009", Outcome.MIGRATED), Map.entry("L010", Outcome.UNMAPPED),
                Map.entry("L011", Outcome.MIGRATED)));
        assertThat(firstRun.exceptions()).extracting(DocumentResult::legacyDocumentId)
                .containsExactlyInAnyOrder("L003", "L004", "L005", "L006", "L007", "L008", "L010");
        assertThat(firstRun.exceptions()).allSatisfy(result -> assertThat(result.reason()).isNotBlank());
        assertThat(results.get("L008").reason()).contains("VALIDATION_FAILED").contains("amount");

        // versions re-encrypted under the tenant key, checksums equal before and after
        DocumentResult receipt = results.get("L001");
        assertThat(receipt.legacyVersions()).isEqualTo(2);
        assertThat(receipt.jdmsVersions()).isEqualTo(2);
        assertThat(receipt.checksums()).allSatisfy(checksum -> assertThat(checksum.match()).isTrue());
        assertThat(receipt.checksums()).extracting(Reconciliation.Checksum::legacySha256)
                .containsExactly(sha256(plain.get("docs/versions/Inbox/r1.pdf.v1")),
                        sha256(plain.get("docs/base/Inbox/r1.pdf")));
        assertThat(receipt.legacyHistory()).isEqualTo(4);
        assertThat(receipt.jdmsHistory()).isEqualTo(4);
        assertThat(receipt.differences()).isEmpty();
        assertThat(receipt.warnings()).isEmpty();
        assertThat(results.get("L011").warnings()).anyMatch(warning -> warning.startsWith("DUPLICATE_CONTENT"));
        assertThat(firstRun.counts().get("docs/RECEIPT").legacy()).isEqualTo(9);

        // what the core holds
        Map<String, Object> stored = coreJdbc.queryForMap("select status, source_system, legacy_created_by, cif, "
                + "folder_id from document where tenant_id = ? and legacy_document_id = 'L001'", tenant.id());
        assertThat(stored).containsEntry("status", "ACTIVE").containsEntry("source_system", "MIGRATION")
                .containsEntry("legacy_created_by", "ahmed").containsEntry("cif", "C001");
        assertThat(stored.get("folder_id")).isNotNull();
        assertThat(coreJdbc.queryForObject("select status from document where tenant_id = ? and legacy_document_id "
                + "= 'L002'", String.class, tenant.id())).isEqualTo("DELETED");
        assertThat(coreJdbc.queryForObject("select count(*) from approval_record r join document d on d.id = "
                + "r.document_id where d.legacy_document_id = 'L001' and r.decision = 'RETURN' and "
                + "r.decision_comment = 'blurred'", Integer.class)).isEqualTo(1);
        JsonNode kyc = core.document(results.get("L009").documentId());
        assertThat(kyc.path("customFields").path("reference_no").asString()).isEqualTo("REF-9");
        assertThat(kyc.path("customFields").path("letter_date").asString()).isEqualTo("2019-03-08");
        assertThat(kyc.path("expiryDate").asString()).isEqualTo("2031-12-31");

        // the legacy auditor role keeps exactly its access; extra access from JDMS baseline rules is listed
        List<PermissionCheck> auditor = firstRun.permissions().stream()
                .filter(check -> check.legacyUser().equals("U_AUD")).toList();
        assertThat(auditor).filteredOn(check -> check.legacyDocumentId().equals("L001")).singleElement()
                .satisfies(check -> {
                    assertThat(check.legacyActions()).containsExactly("VIEW_METADATA", "VIEW_CONTENT");
                    assertThat(check.missing()).isEmpty();
                });
        assertThat(firstRun.permissions()).allSatisfy(check -> assertThat(check.missing()).isEmpty());
        assertThat(firstRun.permissions()).anyMatch(check -> check.legacyUser().equals("U_MAK")
                && !check.extra().isEmpty());

        Path written = firstRun.write(reports);
        assertThat(Files.readString(written)).contains("\"L005\"").contains("CORRUPT");
        String markdown = Files.readString(written.resolveSibling("reconciliation.md"));
        assertThat(markdown).contains("## Exceptions").contains("| L004 | docs/RECEIPT | UNDECRYPTABLE |")
                .contains("Files compared by SHA-256: 5, matching: 5");
    }

    @Test
    @Order(3)
    void reRunsChangeNothing() {
        Reconciliation again = runner(null).run();

        Map<String, Outcome> outcomes = outcomes(again);
        assertThat(outcomes).containsEntry("L001", Outcome.ALREADY_MIGRATED).containsEntry("L009",
                Outcome.ALREADY_MIGRATED);
        assertThat(again.exceptions()).extracting(DocumentResult::legacyDocumentId)
                .containsExactlyInAnyOrderElementsOf(firstRun.exceptions().stream()
                        .map(DocumentResult::legacyDocumentId).toList());
        assertThat(coreJdbc.queryForObject("select count(*) from document where tenant_id = ? and source_system = "
                + "'MIGRATION'", Integer.class, tenant.id())).isEqualTo(4);
    }

    @Test
    @Order(4)
    void deltaModeTakesOnlyWhatChangedAfterTheCutOff() throws Exception {
        Instant cutOff = Instant.now().minus(1, ChronoUnit.MINUTES);
        legacyJdbc.update("insert into dms_document_upload values ('L012', 'docs', 'Receipt 12', 'pdf', 'receipt', 1, "
                + "null, now(), null, 'r12.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C012', null, null, null, "
                + "'ahmed', now(), null, null)");
        legacyJdbc.update("insert into dms_custom_docfield_dts values ('L012', null, null, null, 12)");
        writeLegacyFile("docs/base/r12.pdf", pdf("receipt 12"));
        legacyJdbc.update("update dms_document_upload set checkdate = now() where document_id = 'L001'");

        Reconciliation delta = runner(cutOff).run();

        assertThat(outcomes(delta)).containsExactlyInAnyOrderEntriesOf(Map.of("L012", Outcome.MIGRATED,
                "L001", Outcome.CHANGED_AFTER_MIGRATION));
        assertThat(delta.exceptions()).singleElement().satisfies(result ->
                assertThat(result.reason()).contains("review"));
    }

    @Test
    @Order(5)
    void noKeyMaterialReachesTheLogs() throws Exception {
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(SECRET.getBytes(StandardCharsets.UTF_8)),
                16);
        List<String> forbidden = List.of(SECRET, HexFormat.of().formatHex(key), HexFormat.of().withUpperCase()
                .formatHex(key), Base64.getEncoder().encodeToString(key));
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : event.getThrowableProxy().getMessage());
            forbidden.forEach(secret -> assertThat(line).doesNotContain(secret));
        });
    }

    private MigrationRunner runner(Instant since) {
        MigrationProperties properties = new MigrationProperties(files, null, null, reports, since, false, 20, 50,
                MAPPING);
        return new MigrationRunner(properties, legacy, store, cipher, core);
    }

    private static Map<String, Outcome> outcomes(Reconciliation report) {
        return report.documents().stream()
                .collect(Collectors.toMap(DocumentResult::legacyDocumentId, DocumentResult::outcome));
    }

    private List<String> typeCodes() {
        List<String> codes = new java.util.ArrayList<>();
        core.documentTypes().forEach(type -> codes.add(type.path("code").asString()));
        return codes;
    }

    /** Approves every pending change as an administrator with the given role (not the proposing client). */
    private void approvePending(String role) {
        String admin = TestJwts.user(tenant.code()).username("approver-" + role.toLowerCase()).roles(role)
                .branchScope("ALL").clearance("RESTRICTED").sign();
        RestClient http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + admin).build();
        JsonNode pending = http.get().uri("/api/v1/admin/pending-changes?status=PENDING&size=100").retrieve()
                .body(JsonNode.class);
        for (JsonNode change : pending.path("items")) {
            if (change.path("canDecide").asBoolean()) {
                http.post().uri("/api/v1/admin/pending-changes/{id}/approve", change.path("id").asString())
                        .header("Idempotency-Key", "e2e-" + change.path("id").asString()).retrieve().toBodilessEntity();
            }
        }
    }

    private void writeLegacyFile(String relative, byte[] content) throws Exception {
        plain.put(relative, content);
        Path file = files.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, encrypt(content));
    }

    private static byte[] encrypt(byte[] content) throws Exception {
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(SECRET.getBytes(StandardCharsets.UTF_8)),
                16);
        Cipher legacyCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        legacyCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return legacyCipher.doFinal(content);
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n% " + text + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
