package com.jmr.jdms.migration.legacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the legacy JDMS tables (reference §2). All SQL of the tool lives here, uses bound parameters
 * only, and sticks to ANSI SQL so it runs on the bank's Oracle as on the PostgreSQL test fixture.
 */
@Repository
public class LegacyRepository {

    /** A legacy document row ({@code DMS_DOCUMENT_UPLOAD}) with its custom field slots ({@code cust_*}). */
    public record LegacyDocument(String documentId, String cabinet, String title, String extension, String docType,
            int docVersion, LocalDate expiryDate, Instant createdAt, String folderPath, String fileName,
            String branchCode, String roleId, String permission, String wfStatus, String status, String customerId,
            String accountNo, String contractRef, String customerName, String maker, Instant makeDate,
            String checker, Instant checkDate, Map<String, Object> slots) {
    }

    public record FolderSetting(String cabinet, String baseFolder, String versionFolder, String deleteFolder) {
    }

    public record LegacyType(String cabinet, String docTypeId, String description, String category) {
    }

    /** {@code fieldId} names the slot ({@code cust_str_field3}); {@code fieldName} becomes the JDMS field key. */
    public record LegacyField(String cabinet, String docTypeId, String fieldName, String fieldId, String label,
            boolean mandatory, int order, Integer length) {
    }

    public record LegacyStep(String stage, String action, String user, String remarks, Instant at) {
    }

    public record LegacyUser(String userId, String userName, String branchCode, List<String> roles) {
    }

    public record LegacyFolder(String cabinet, String name, String path, String roleId, String permission) {
    }

    private final JdbcTemplate jdbc;

    public LegacyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All documents, or in delta mode those created or changed after {@code since}; ordered by legacy ID. */
    public List<LegacyDocument> documents(Optional<Instant> since) {
        String sql = """
                select u.document_id, u.appln_id, u.document_name, u.document_type, u.doc_type, u.doc_version,
                       u.expiry_date, u.creation_date, u.folderpath, u.file_name, u.brn_cd, u.role_id,
                       u.doc_permission, u.wf_status, u.status, u.customer_id, u.account_no, u.contract_ref_no,
                       u.cust_name, u.makerid, u.makedate, u.checkerid, u.checkdate, c.*
                from dms_document_upload u
                left join dms_custom_docfield_dts c on c.document_id = u.document_id
                """;
        if (since.isPresent()) {
            Timestamp cutOff = Timestamp.from(since.get());
            return jdbc.query(sql + " where u.creation_date > ? or u.makedate > ? or u.checkdate > ?"
                    + " order by u.document_id", this::document, cutOff, cutOff, cutOff);
        }
        return jdbc.query(sql + " order by u.document_id", this::document);
    }

    public List<FolderSetting> folderSettings() {
        return jdbc.query("select appln_name, base_folder, version_folder, delete_folder from dms_folder_setting",
                (row, n) -> new FolderSetting(row.getString(1), row.getString(2), row.getString(3), row.getString(4)));
    }

    public List<LegacyType> types() {
        return jdbc.query("select cabenit_name, doc_type_id, doc_type_desc, category from doc_type_master "
                + "order by cabenit_name, doc_type_id",
                (row, n) -> new LegacyType(row.getString(1), row.getString(2), row.getString(3), row.getString(4)));
    }

    public List<LegacyField> fields() {
        return jdbc.query("""
                select cabenit_name, doc_type_id, field_name, field_id, field_label, field_mandatory, field_order,
                       field_length
                from doc_field_details order by cabenit_name, doc_type_id, field_order
                """, (row, n) -> new LegacyField(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), "Y".equalsIgnoreCase(row.getString(6)), row.getInt(7),
                (Integer) row.getObject(8, Integer.class)));
    }

    public List<LegacyStep> history(String documentId) {
        return jdbc.query("select stage_name, action, user_id, remarks, action_date from wf_workflowhistory "
                + "where document_id = ? order by action_date, history_id",
                (row, n) -> new LegacyStep(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        instant(row, 5)), documentId);
    }

    /** Users with their roles, for the permission comparison (at most {@code limit}, by user ID). */
    public List<LegacyUser> users(int limit) {
        Map<String, LegacyUser> users = new LinkedHashMap<>();
        jdbc.query("""
                select u.user_id, u.user_name, u.brn_cd, r.role_id
                from cm_users u join cm_userroles r on r.user_id = u.user_id
                order by u.user_id, r.role_id
                """, (ResultSet row) -> {
            String id = row.getString(1);
            LegacyUser user = users.get(id);
            if (user == null && users.size() < limit) {
                user = new LegacyUser(id, row.getString(2), row.getString(3), new java.util.ArrayList<>());
                users.put(id, user);
            }
            if (user != null) {
                user.roles().add(row.getString(4));
            }
        });
        return List.copyOf(users.values());
    }

    public List<LegacyFolder> folders() {
        return jdbc.query("select appln_id, folder_name, folder_path, role_id, permission from dms_folder_detail "
                + "order by appln_id, folder_path",
                (row, n) -> new LegacyFolder(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5)));
    }

    private LegacyDocument document(ResultSet row, int n) throws SQLException {
        Map<String, Object> slots = new LinkedHashMap<>();
        var meta = row.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String column = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            if (column.startsWith("cust_")) {
                Object value = row.getObject(i);
                if (value != null) {
                    slots.put(column, value);
                }
            }
        }
        java.sql.Date expiry = row.getDate("expiry_date");
        return new LegacyDocument(row.getString("document_id"), row.getString("appln_id"),
                row.getString("document_name"), row.getString("document_type"), row.getString("doc_type"),
                Math.max(1, row.getInt("doc_version")), expiry == null ? null : expiry.toLocalDate(),
                instant(row, "creation_date"), row.getString("folderpath"), row.getString("file_name"),
                row.getString("brn_cd"), row.getString("role_id"), row.getString("doc_permission"),
                row.getString("wf_status"), row.getString("status"), row.getString("customer_id"),
                row.getString("account_no"), row.getString("contract_ref_no"), row.getString("cust_name"),
                row.getString("makerid"), instant(row, "makedate"), row.getString("checkerid"),
                instant(row, "checkdate"), slots);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
