-- One legacy bank, cabinet "docs": each document exercises one migration outcome (see MigrationEndToEndIT).
insert into dms_folder_setting values ('docs', 'docs/base', 'docs/versions', 'docs/deleted');

insert into doc_type_master values ('docs', 'receipt', 'Receipt', 'FIN');
insert into doc_type_master values ('docs', 'kyc_letter', 'KYC letter', 'KYC');

insert into doc_field_details values ('docs', 'receipt', 'amount', 'cust_num_field1', 'Amount', 'Y', 1, null);
insert into doc_field_details values ('docs', 'receipt', 'note', 'cust_str_field1', 'Note', 'N', 2, 100);
insert into doc_field_details values ('docs', 'kyc_letter', 'Reference No', 'cust_str_field2', 'Reference', 'N', 1, 50);
insert into doc_field_details values ('docs', 'kyc_letter', 'Letter Date', 'cust_dt_field1', 'Letter date', 'N', 2, null);

insert into dms_document_upload values ('L001', 'docs', 'Receipt 1', 'pdf', 'receipt', 2, null, '2019-01-15 08:30:00',
    '/Inbox', 'r1.pdf', '001', 'R_AUD', 'V', 'APPROVED', 'ACTIVE', 'C001', 'A-1', null, 'Ahmed Ali', 'ahmed',
    '2019-02-15 08:30:00', 'fatima', '2019-02-15 10:00:00');
insert into dms_document_upload values ('L002', 'docs', 'Receipt 2', 'pdf', 'receipt', 1, null, '2019-03-01 08:00:00',
    '/Inbox', 'r2.pdf', '001', null, null, 'APPROVED', 'DELETED', 'C002', null, null, null, 'ahmed',
    '2019-03-01 08:00:00', 'fatima', '2019-03-01 09:00:00');
insert into dms_document_upload values ('L003', 'docs', 'Receipt 3', 'pdf', 'receipt', 1, null, '2019-03-02 08:00:00',
    null, 'missing.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C003', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L004', 'docs', 'Receipt 4', 'pdf', 'receipt', 1, null, '2019-03-03 08:00:00',
    null, 'damaged.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C004', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L005', 'docs', 'Receipt 5', 'pdf', 'receipt', 1, null, '2019-03-04 08:00:00',
    null, 'garbage.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C005', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L006', 'docs', 'Receipt 6', 'pdf', 'receipt', 1, null, '2019-03-05 08:00:00',
    null, 'r6.pdf', '001', null, null, 'WORKINPROGRESS', 'ACTIVE', 'C006', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L007', 'docs', 'Odd 7', 'pdf', 'unknown_type', 1, null, '2019-03-06 08:00:00',
    null, 'r7.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C007', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L008', 'docs', 'Receipt 8', 'pdf', 'receipt', 1, null, '2019-03-07 08:00:00',
    null, 'r8.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C008', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L009', 'docs', 'KYC letter 9', 'pdf', 'kyc_letter', 1, '2031-12-31',
    '2019-03-08 08:00:00', null, 'k9.pdf', '001', null, null, 'APPROVED', 'ACTIVE', 'C009', null, 'LN-9', null,
    'ahmed', null, null, null);
insert into dms_document_upload values ('L010', 'docs', 'Receipt 10', 'pdf', 'receipt', 1, null, '2019-03-09 08:00:00',
    null, 'r10.pdf', '001', 'R_XX', 'V', 'APPROVED', 'ACTIVE', 'C010', null, null, null, 'ahmed', null, null, null);
insert into dms_document_upload values ('L011', 'docs', 'Receipt 11', 'pdf', 'receipt', 1, null, '2019-03-10 08:00:00',
    null, 'r11.pdf', '002', null, null, 'APPROVED', 'ACTIVE', 'C011', null, null, null, 'ahmed', null, null, null);

insert into dms_custom_docfield_dts values ('L001', 'ok', null, null, 125.500);
insert into dms_custom_docfield_dts values ('L002', null, null, null, 10);
insert into dms_custom_docfield_dts values ('L003', null, null, null, 1);
insert into dms_custom_docfield_dts values ('L004', null, null, null, 1);
insert into dms_custom_docfield_dts values ('L005', null, null, null, 1);
insert into dms_custom_docfield_dts values ('L006', null, null, null, 1);
insert into dms_custom_docfield_dts values ('L009', null, 'REF-9', '2019-03-08', null);
insert into dms_custom_docfield_dts values ('L010', null, null, null, 1);
insert into dms_custom_docfield_dts values ('L011', null, null, null, 7);

insert into wf_workflowhistory values (1, 'L001', 'Maker', 'SUBMITTED', 'ahmed', null, '2019-01-15 08:30:00');
insert into wf_workflowhistory values (2, 'L001', 'Checker', 'RETURNED', 'fatima', 'blurred', '2019-01-15 09:00:00');
insert into wf_workflowhistory values (3, 'L001', 'Maker', 'SUBMITTED', 'ahmed', 'rescanned', '2019-02-15 08:30:00');
insert into wf_workflowhistory values (4, 'L001', 'Checker', 'APPROVED', 'fatima', null, '2019-02-15 10:00:00');

insert into cm_users values ('U_AUD', 'Auditor', '002');
insert into cm_users values ('U_MAK', 'Maker', '001');
insert into cm_userroles values ('U_AUD', 'R_AUD');
insert into cm_userroles values ('U_MAK', 'R_MAK');

insert into dms_folder_detail values (1, 'docs', 'Archive', '/Archive', 'R_AUD', 'V');
