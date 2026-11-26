package com.jmr.jdms.support;

import com.jmr.jdms.metadata.domain.Cabinet;
import com.jmr.jdms.metadata.domain.CabinetRepository;
import com.jmr.jdms.metadata.domain.DocumentType;
import com.jmr.jdms.metadata.domain.DocumentTypeAttributes;
import com.jmr.jdms.metadata.domain.DocumentTypeRepository;
import com.jmr.jdms.metadata.domain.FieldDataType;
import com.jmr.jdms.metadata.domain.FieldDefinition;
import com.jmr.jdms.metadata.domain.FieldDefinitionRepository;
import com.jmr.jdms.metadata.domain.FieldSpec;
import com.jmr.jdms.metadata.domain.Folder;
import com.jmr.jdms.metadata.domain.FolderRepository;
import com.jmr.jdms.metadata.domain.Lov;
import com.jmr.jdms.metadata.domain.LovRepository;
import com.jmr.jdms.metadata.domain.LovValue;
import com.jmr.jdms.metadata.domain.LovValueRepository;
import com.jmr.jdms.metadata.domain.UploadChannel;
import com.jmr.jdms.shared.Classification;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.tenant.TenantScope;
import com.jmr.jdms.support.TestTenants.CreatedTenant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A metadata model for document tests, created directly (maker-checker is proven in B-02 tests):
 * <ul>
 *   <li>cabinet DOCS (10 MB per file) with folder Inbox, types RECEIPT (no approval, CIF required; fields amount,
 *       currency, note, secret_note (sensitive)), CONTRACT (single approval, metadata changes need approval) and
 *       UI_ONLY (UI channel only)</li>
 *   <li>cabinet SMALL (1 MB per file, 2 MB quota) with type SMALL_DOC</li>
 * </ul>
 */
@Component
public class TestMetadata {

    private final CabinetRepository cabinets;
    private final DocumentTypeRepository types;
    private final FieldDefinitionRepository fields;
    private final LovRepository lovs;
    private final LovValueRepository lovValues;
    private final FolderRepository folders;
    private final TransactionTemplate transactions;

    public TestMetadata(CabinetRepository cabinets, DocumentTypeRepository types, FieldDefinitionRepository fields,
            LovRepository lovs, LovValueRepository lovValues, FolderRepository folders,
            TransactionTemplate transactions) {
        this.cabinets = cabinets;
        this.types = types;
        this.fields = fields;
        this.lovs = lovs;
        this.lovValues = lovValues;
        this.folders = folders;
        this.transactions = transactions;
    }

    public record Model(UUID docsCabinetId, UUID smallCabinetId, UUID inboxFolderId, UUID receiptTypeId,
            UUID contractTypeId) {
    }

    public Model create(CreatedTenant tenant) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> {
            Cabinet docs = cabinets.save(new Cabinet(Ids.newId(), "DOCS", "Documents", 10, 1000, null));
            Cabinet small = cabinets.save(new Cabinet(Ids.newId(), "SMALL", "Small", 1, 2, null));
            Folder inbox = folders.save(new Folder(Ids.newId(), docs.getId(), null, "Inbox", "/Inbox"));
            Lov currency = lovs.save(new Lov(Ids.newId(), "CURRENCY", "Currency"));
            lovValues.save(new LovValue(currency.getId(), "OMR", "OMR", "ريال", 1, true));
            lovValues.save(new LovValue(currency.getId(), "USD", "USD", "دولار", 2, true));

            DocumentType receipt = types.save(new DocumentType(Ids.newId(), docs.getId(), "RECEIPT",
                    attributes(Set.of(UploadChannel.UI, UploadChannel.INTEGRATION), Classification.INTERNAL, null,
                            false, Set.of("cif"))));
            fields.save(new FieldDefinition(Ids.newId(), receipt.getId(), spec("amount", FieldDataType.NUMBER, true,
                    null, null, null, false, 1)));
            fields.save(new FieldDefinition(Ids.newId(), receipt.getId(), spec("currency", FieldDataType.LOV, false,
                    null, "OMR", "CURRENCY", false, 2)));
            fields.save(new FieldDefinition(Ids.newId(), receipt.getId(), spec("note", FieldDataType.TEXT, false, 100,
                    null, null, false, 3)));
            fields.save(new FieldDefinition(Ids.newId(), receipt.getId(), spec("secret_note", FieldDataType.TEXT,
                    false, 100, null, null, true, 4)));

            DocumentType contract = types.save(new DocumentType(Ids.newId(), docs.getId(), "CONTRACT",
                    attributes(Set.of(UploadChannel.UI, UploadChannel.INTEGRATION), Classification.CONFIDENTIAL,
                            "single-approval", true, Set.of())));
            types.save(new DocumentType(Ids.newId(), docs.getId(), "UI_ONLY", attributes(Set.of(UploadChannel.UI),
                    Classification.INTERNAL, null, false, Set.of())));
            types.save(new DocumentType(Ids.newId(), small.getId(), "SMALL_DOC", attributes(
                    Set.of(UploadChannel.UI, UploadChannel.INTEGRATION), Classification.INTERNAL, null, false,
                    Set.of())));
            return new Model(docs.getId(), small.getId(), inbox.getId(), receipt.getId(), contract.getId());
        }));
    }

    /**
     * Adds a document type to the tenant's DOCS cabinet with an optional NUMBER field {@code amount} (INTERNAL,
     * metadata changes need approval). Returns its ID.
     */
    public UUID addApprovalType(CreatedTenant tenant, String code, String processKey) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> {
            Cabinet docs = cabinets.findByCode("DOCS").orElseThrow();
            DocumentType type = types.save(new DocumentType(Ids.newId(), docs.getId(), code,
                    attributes(Set.of(UploadChannel.UI, UploadChannel.INTEGRATION), Classification.INTERNAL,
                            processKey, true, Set.of())));
            fields.save(new FieldDefinition(Ids.newId(), type.getId(), spec("amount", FieldDataType.NUMBER, false,
                    null, null, null, false, 1)));
            return type.getId();
        }));
    }

    /**
     * Adds an INVOICE type (INTERNAL, no approval) with {@code currency} (LOV, searchable facet), {@code note} (TEXT,
     * searchable) and {@code card_no} (TEXT, sensitive) for search tests.
     */
    public UUID addSearchType(CreatedTenant tenant) {
        return TenantScope.call(tenant.id(), () -> transactions.execute(status -> {
            Cabinet docs = cabinets.findByCode("DOCS").orElseThrow();
            DocumentType type = types.save(new DocumentType(Ids.newId(), docs.getId(), "INVOICE",
                    attributes(Set.of(UploadChannel.UI, UploadChannel.INTEGRATION), Classification.INTERNAL, null,
                            false, Set.of())));
            fields.save(new FieldDefinition(Ids.newId(), type.getId(), new FieldSpec("currency", "currency", "حقل",
                    FieldDataType.LOV, false, null, null, "CURRENCY", true, true, false, 1, 1)));
            fields.save(new FieldDefinition(Ids.newId(), type.getId(), spec("note", FieldDataType.TEXT, false, 200,
                    null, null, false, 2)));
            fields.save(new FieldDefinition(Ids.newId(), type.getId(), spec("card_no", FieldDataType.TEXT, false, 30,
                    null, null, true, 3)));
            return type.getId();
        }));
    }

    private static DocumentTypeAttributes attributes(Set<UploadChannel> channels, Classification classification,
            String processKey, boolean metaApproval, Set<String> required) {
        return new DocumentTypeAttributes("Type", "نوع", "TEST", channels, classification, processKey, metaApproval,
                false, false, required);
    }

    private static FieldSpec spec(String key, FieldDataType type, boolean mandatory, Integer maxLength,
            String defaultValue, String lovCode, boolean sensitive, int order) {
        return new FieldSpec(key, key, "حقل", type, mandatory, maxLength, defaultValue, lovCode, true, false,
                sensitive, order, 1);
    }
}
