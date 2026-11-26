# 06 — Legacy JDMS reference

Source: `SKNANB/jdms` (Java 8, Spring 4, Hibernate 4, Oracle, JBoss/WebLogic). Use this file to understand
behaviour, to map data for migration and to adapt existing integrations. **Do not copy legacy code or
patterns.** Known defects in the legacy code (do not reproduce): hard-coded AES key and ECB mode, plaintext
credentials in config, SQL/HQL string concatenation, search index storing plaintext content, decrypted temp
files on disk, CSRF disabled, server-built HTML, runtime Java compilation for rules, trust in caller-supplied
user and role IDs on the external API, filesystem paths used as identifiers.

## 1. Legacy concepts

| Legacy concept | Meaning | JDMS Next equivalent |
| --- | --- | --- |
| `entity_id` | Bank / legal entity | `tenant` |
| `brn_cd` | Branch code | `branch` |
| `appln_id` / `appln_name` | Application area, used like a cabinet (e.g. CUSTOMERS) with its own base, backup, recovery, version and delete folders and size quotas | `cabinet` |
| `cabenit_name` (sic) / `cabinet_name` | Cabinet on document types and documents | `cabinet` |
| Folder (`DMS_FOLDER_DETAIL`) | Physical directory on disk with role and permission | Virtual `folder` |
| `doc_type_id` / `document_type` | Document type within a cabinet | `document_type` |
| `DOC_FIELD_DETAILS` | Dynamic form field definitions per type | `field_definition` |
| `DMS_CUSTOM_DOCFIELD_DTS` | 25 text, 10 date, 10 number slots per document | `document.custom_fields` (JSON) |
| `recstatus` | Maker-checker record status (`A` = authorised) | Approval via `pending_change` / process |
| `makerid/makedate/checkerid/checkdate` | Maker-checker columns on every table | `approval_record` + audit |
| `wf_assigned_name` → `WF_ASSIGNMENT` → `workflow_id` | Screen/feature mapped to a workflow | `document_type.process_key` |
| `current_stage`, `wf_status` (`NEW`, `WORKINPROGRESS`, `APPROVED`, `REJECTED`) | Workflow position on the record | Flowable task + `document.status` |
| `WF_ALL_STAGES` (`type`, `assigneduser`, `usertype`) | Stage definitions; stage types include user, role, committee, auto, end | BPMN user tasks / candidate groups |
| `WF_STAGES_ORDER`, `WF_BRANCHING`, `WF_COMPOUND_RULES` | Stage order and rule-based branching (rules compiled at runtime) | BPMN sequence + DMN decision tables |
| `CM_COMMITTEE_MASTER.min_participants` | Committee quorum | `committee.min_quorum` |
| `WF_WORKFLOWHISTORY` | Approval actions per stage | `approval_record` |
| `doc_permission`, `role_id` on documents/folders | Role-based access per item | `access_rule` |
| `CM_ROLEPERMISSIONS` | Screen-level flags: view, save, update, delete, auth, auto-auth, access-all, branch-access-all, submit, cancel, unlock | Roles + `access_rule` actions + UI capability flags |
| Version folder + `doc_version` counter | Older files copied to a version folder | Immutable `document_version` rows |
| Delete folder + `DMS_RECOVERY` | Recycle bin with approval to recover | Soft delete + restore |
| `DMS_BACKUP` | User-requested folder backups with approval | Platform backup (not a user feature) |
| `DMS_RETRIEVAL_REQUEST` | Request to access a document, with approval | `retrieval_request` |
| `physical_doc_location`, `physical_doc_ref_id` | Physical file location and reference | Same fields on `document` |
| `link_document_id` | Related document | `document_link` |
| `expiry_date` | Document expiry (e.g. passport) | Same; also a retention trigger |
| `CERTIFICATES` | Generated certificate PDFs with digital signature and QR verification link | Later phase |
| `CM_HISTORY` | Audit: table, primary key, operation, old/new data, client and server IP | `audit_event` |
| `CM_POLICY_SETTINGS` | Password and session policy | Keycloak realm policies |
| LDAP settings | Directory login | Keycloak LDAP/AD federation |

## 2. Main legacy tables (columns)

- **DMS_DOCUMENT_UPLOAD** (PK `document_id`): entity_id, appln_id, document_name, document_type (file extension), doc_type, doc_category, doc_version, expiry_date, creation_date, folderpath, file_name, file_path, brn_cd, role_id, doc_permission, current_stage, wf_assigned_name, workflow_id, wf_status, status (`ACTIVE`), comments, link_document_id, physical_doc_location, physical_doc_ref_id, doc_remarks, batch_file_name, unique_number, cabnet_name/cabinet_name, cust_name, page_num, migration_doc_id, is_index, plus maker-checker columns. Business keys used by integrations (customer_id, customer_no, account_no, collateral_code, contract_ref_no, transaction_ref_no, application_id, application_ref_no) appear on the entity in some builds — check the actual schema of each client.
- **DMS_CUSTOM_DOCFIELD_DTS** (PK `document_id`): cust_str_field1–25, cust_dt_field1–10, cust_num_field1–10, brn_cd, entity_id, migration_doc_id, maker-checker columns. Slot meaning comes from `DOC_FIELD_DETAILS.field_id` per type.
- **DOC_TYPE_MASTER** (PK cabenit_name + doc_type_id): doc_type_desc, doc_level_path, category, channel_flag.
- **DOC_FIELD_DETAILS**: doc_type_id, cabenit_name, field_name, field_id, field_label, field_type, field_form_flag, field_mandatory, field_order, field_default_value, field_class, field_function, field_style, field_disable, field_readonly, row_order, lov_id_function, field_length.
- **DMS_DIRECTORY_MAINTENANCE** (PK document_type): appln_id, folder_path, mig_dynamic_map_fields, mandatory_fields.
- **DMS_FOLDER_SETTING** (PK appln_name): base_folder, backup_folder, recovery_folder, version_folder, delete_folder, max_file_size_mb, used_file_size_mb, per_file_size_mb, single/multi upload, copy, move, delete, backup, recovery size limits.
- **DMS_FOLDER_DETAIL** (PK folder_id): appln_id, folder_name, folder_path, base_folder_path, user_id, role_id, permission.
- **DMS_BULKDOC_STAGE_UPLOAD**: staging for Excel-driven bulk upload; index fields include passportno, accounttype, cifnumber, issuedate, name, pagenum, scandate, accountnumber, institution, amount, applicationdate, birthcertificateno, checkamount, closeddate, collateralcode, customername, dateopened, dateposted, deathcertificateno, driverslicenseno, greencardno, idxcabinet, idxtype, intlaccountnumber, intlbankname, invoiceamount, invoicenumber, branchnumber, taxid, workpermitno, schoolidnumber, reference, paymentdescription, paymentamount, nationalidno, marriagecertificateno, loanoriginationdate, socialsecurityno, officer, bulk_upload_status, excel_file_name. These show the real document types and index fields banks use; use them to design seed document types.
- **DMS_RETRIEVAL_REQUEST** (PK request_id): document fields + request_creation_date + workflow columns.
- **WF_WORKFLOW**, **WF_ASSIGNMENT**, **WF_ALL_STAGES**, **WF_STAGES_ORDER**, **WF_BRANCHING**, **WF_COMPOUND_RULES**, **WF_WORKFLOWHISTORY**, **CM_COMMITTEE_MASTER**, **CM_COMMITTEE_USER**.
- **CM_USERS**, **CM_ROLEMASTER**, **CM_USERROLES**, **CM_ROLEPERMISSIONS**, **CM_POLICY_SETTINGS**, **CM_HISTORY**.

## 3. Seed document types for the PoC (derived from legacy index fields)

| Cabinet | Type | Fields (besides standard banking keys) |
| --- | --- | --- |
| CUSTOMERS | PASSPORT | passport_no, nationality (LOV), issue_date, expiry_date |
| CUSTOMERS | NATIONAL_ID | national_id_no, issue_date, expiry_date |
| CUSTOMERS | ACCOUNT_OPENING_FORM | account_type (LOV), date_opened, officer |
| LOANS | LOAN_AGREEMENT | facility_type (LOV), amount, currency (LOV), loan_origination_date |
| LOANS | COLLATERAL_DEED | collateral_type (LOV), valuation_amount, valuation_date |
| LOANS | SALARY_CERTIFICATE | employer, monthly_salary, issue_date |

## 4. Legacy integration contract (FLEXCUBE and other callers)

Legacy endpoints (Spring MVC, `/jdms/...`): `/document-upload`, `/document-download`, `/document-details` (GET),
`/image-download`, `/upload-file`, `/download-file`, `/delete-file`, `/bulk-upload-files`, `/bulk-delete-files`,
`/auth-uploaded-files`, `/convert-tiff-to-jpeg`, plus bulk Excel and migration endpoints. Requests carry an
XML or JSON message with:

- **FileHeader**: `entity_id`, `appln_id`, `role_id`, `user_id` (trusted as sent — insecure)
- **FileDetails**: `document_id`, `document_name`, `document_type`, `doc_type`, `category`, `creation_date`, `expiry_date`, `accountType`, `cifNumber`, `issueDate`, `name`, `passportNo`, `external_system` (mandatory), `doc_version`, `applicationId`, `applicationRefNo`, `customerId`, `functionId`, `keyId`, `accountNo`, `customerNo`, `collateralCode`, `contractRefNo`, `transactionRefNo`, file content (Base64)
- Errors returned as `errorCode` (e.g. `ERR-001`) + `errorDescription`

Mapping to `/integration/v1/documents`: `entity_id` → token tenant; `appln_id` → `cabinetCode`; `doc_type` →
`documentTypeCode`; `user_id`/`role_id` → dropped (client identity from OAuth2); `cifNumber`/`customerId` →
`cif`; `customerNo` → `customerNo`; `accountNo`; `collateralCode`; `contractRefNo` → `contractRef`;
`transactionRefNo` → `transactionRef`; `applicationRefNo` → `applicationRef`; `external_system`; remaining
type-specific fields → `customFields`. `functionId`/`keyId` (FLEXCUBE screen and key) → `customFields.fcFunctionId`
and `customFields.fcKeyId` until their use is confirmed (O-03).

## 5. Migration mapping summary

| Legacy | Target | Notes |
| --- | --- | --- |
| DMS_DOCUMENT_UPLOAD row | `document` + `document_version` | `legacy_document_id` = document_id; status from wf_status/status; folder from folderpath → virtual folder |
| Files under base folder (AES-encrypted) | Blob, re-encrypted | Decrypt with legacy key (AES, key = first 16 bytes of SHA-1 of the legacy secret, ECB/PKCS5 padding — confirm against a sample); verify by opening the decrypted file |
| Files under version folder | Older `document_version` rows | Order by timestamp/version counter |
| Files under delete folder | `document` with `status = DELETED` | |
| DMS_CUSTOM_DOCFIELD_DTS | `custom_fields` | Slot → field key via DOC_FIELD_DETAILS |
| DOC_TYPE_MASTER + DOC_FIELD_DETAILS | `document_type` + `field_definition` | |
| WF_WORKFLOWHISTORY | `approval_record` | In-flight items: finish in JDMS or migrate with current stage |
| doc_permission / role_id / CM_ROLEPERMISSIONS | `access_rule` | Compare effective permissions per sampled user |
| DMS_RETRIEVAL_REQUEST | `retrieval_request` | Open requests only, or all for history (client decision) |
| CM_HISTORY | Archived as an exported file, not imported into the new hash chain | Keep for evidence |
| Lucene index | Not migrated | Rebuilt from new pipeline |
