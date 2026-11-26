-- Local development metadata for tenant BANK1 (B-02 seed): cabinets CUSTOMERS and LOANS, six document types with
-- fields derived from the legacy JDMS index fields (docs/06-legacy-jdms-reference.md §3) and their lists of values.
-- Seeded rows bypass maker-checker; in real tenants this model is built through the admin API.
-- expiry_date and the banking keys (cif, account_no, contract_ref, collateral_code) are standard document fields,
-- so types require them through required_standard_fields instead of defining custom fields.

-- Cabinets
insert into cabinet (id, tenant_id, code, name, max_file_size_mb, quota_mb, default_process_key, status,
                     created_at, created_by, updated_at, updated_by, version_lock)
select c.id, t.id, c.code, c.name, 100, 512000, 'single-approval', 'ACTIVE', now(), 'seed', now(), 'seed', 0
from (values ('01900000-0000-7000-8000-000000001001'::uuid, 'CUSTOMERS', 'Customers'),
             ('01900000-0000-7000-8000-000000001002'::uuid, 'LOANS', 'Loans and credit')) as c (id, code, name)
         join tenant t on t.code = 'BANK1'
on conflict (tenant_id, code) do nothing;

-- Lists of values
insert into lov (id, tenant_id, code, name, created_at, created_by, updated_at, updated_by, version_lock)
select l.id, t.id, l.code, l.name, now(), 'seed', now(), 'seed', 0
from (values ('01900000-0000-7000-8000-000000002001'::uuid, 'NATIONALITY', 'Nationality'),
             ('01900000-0000-7000-8000-000000002002'::uuid, 'ACCOUNT_TYPE', 'Account type'),
             ('01900000-0000-7000-8000-000000002003'::uuid, 'FACILITY_TYPE', 'Credit facility type'),
             ('01900000-0000-7000-8000-000000002004'::uuid, 'CURRENCY', 'Currency'),
             ('01900000-0000-7000-8000-000000002005'::uuid, 'COLLATERAL_TYPE', 'Collateral type'))
         as l (id, code, name)
         join tenant t on t.code = 'BANK1'
on conflict (tenant_id, code) do nothing;

insert into lov_value (id, tenant_id, lov_id, code, label_en, label_ar, display_order, active,
                       created_at, created_by, updated_at, updated_by, version_lock)
select gen_random_uuid(), l.tenant_id, l.id, v.code, v.label_en, v.label_ar, v.display_order, true,
       now(), 'seed', now(), 'seed', 0
from (values ('NATIONALITY', 'OM', 'Oman', 'عُمان', 1),
             ('NATIONALITY', 'AE', 'United Arab Emirates', 'الإمارات العربية المتحدة', 2),
             ('NATIONALITY', 'SA', 'Saudi Arabia', 'المملكة العربية السعودية', 3),
             ('NATIONALITY', 'BH', 'Bahrain', 'البحرين', 4),
             ('NATIONALITY', 'KW', 'Kuwait', 'الكويت', 5),
             ('NATIONALITY', 'QA', 'Qatar', 'قطر', 6),
             ('NATIONALITY', 'EG', 'Egypt', 'مصر', 7),
             ('NATIONALITY', 'IN', 'India', 'الهند', 8),
             ('NATIONALITY', 'PK', 'Pakistan', 'باكستان', 9),
             ('NATIONALITY', 'GB', 'United Kingdom', 'المملكة المتحدة', 10),
             ('ACCOUNT_TYPE', 'CURRENT', 'Current account', 'حساب جاري', 1),
             ('ACCOUNT_TYPE', 'SAVINGS', 'Savings account', 'حساب توفير', 2),
             ('ACCOUNT_TYPE', 'FIXED_DEPOSIT', 'Fixed deposit', 'وديعة ثابتة', 3),
             ('ACCOUNT_TYPE', 'CALL_DEPOSIT', 'Call deposit', 'وديعة تحت الطلب', 4),
             ('FACILITY_TYPE', 'PERSONAL_LOAN', 'Personal loan', 'قرض شخصي', 1),
             ('FACILITY_TYPE', 'HOME_LOAN', 'Home loan', 'قرض سكني', 2),
             ('FACILITY_TYPE', 'AUTO_LOAN', 'Auto loan', 'قرض سيارة', 3),
             ('FACILITY_TYPE', 'OVERDRAFT', 'Overdraft', 'سحب على المكشوف', 4),
             ('FACILITY_TYPE', 'CREDIT_CARD', 'Credit card', 'بطاقة ائتمان', 5),
             ('FACILITY_TYPE', 'CORPORATE_TERM_LOAN', 'Corporate term loan', 'قرض لأجل للشركات', 6),
             ('CURRENCY', 'OMR', 'Omani rial', 'ريال عماني', 1),
             ('CURRENCY', 'AED', 'UAE dirham', 'درهم إماراتي', 2),
             ('CURRENCY', 'SAR', 'Saudi riyal', 'ريال سعودي', 3),
             ('CURRENCY', 'USD', 'US dollar', 'دولار أمريكي', 4),
             ('CURRENCY', 'EUR', 'Euro', 'يورو', 5),
             ('CURRENCY', 'INR', 'Indian rupee', 'روبية هندية', 6),
             ('COLLATERAL_TYPE', 'PROPERTY', 'Real estate', 'عقار', 1),
             ('COLLATERAL_TYPE', 'VEHICLE', 'Vehicle', 'مركبة', 2),
             ('COLLATERAL_TYPE', 'DEPOSIT', 'Cash deposit', 'وديعة نقدية', 3),
             ('COLLATERAL_TYPE', 'SHARES', 'Listed shares', 'أسهم مدرجة', 4),
             ('COLLATERAL_TYPE', 'GOLD', 'Gold', 'ذهب', 5),
             ('COLLATERAL_TYPE', 'GUARANTEE', 'Bank guarantee', 'ضمان بنكي', 6))
         as v (lov_code, code, label_en, label_ar, display_order)
         join lov l on l.code = v.lov_code
         join tenant t on t.id = l.tenant_id and t.code = 'BANK1'
on conflict (tenant_id, lov_id, code) do nothing;

-- Document types
insert into document_type (id, tenant_id, cabinet_id, code, name_en, name_ar, category, channels,
                           default_classification, process_key, meta_change_needs_approval,
                           delete_requires_approval, declare_record_on_approval, required_standard_fields, status,
                           created_at, created_by, updated_at, updated_by, version_lock)
select d.id, c.tenant_id, c.id, d.code, d.name_en, d.name_ar, d.category, '["INTEGRATION", "UI"]'::jsonb,
       d.classification, d.process_key, true, true, d.declare_record, d.required::jsonb, 'ACTIVE',
       now(), 'seed', now(), 'seed', 0
from (values ('01900000-0000-7000-8000-000000003001'::uuid, 'CUSTOMERS', 'PASSPORT', 'Passport', 'جواز السفر',
              'KYC', 'CONFIDENTIAL', 'single-approval', false, '["cif", "expiry_date"]'),
             ('01900000-0000-7000-8000-000000003002'::uuid, 'CUSTOMERS', 'NATIONAL_ID', 'National ID card',
              'بطاقة الهوية الوطنية', 'KYC', 'CONFIDENTIAL', 'single-approval', false, '["cif", "expiry_date"]'),
             ('01900000-0000-7000-8000-000000003003'::uuid, 'CUSTOMERS', 'ACCOUNT_OPENING_FORM',
              'Account opening form', 'نموذج فتح حساب', 'ACCOUNT', 'CONFIDENTIAL', 'single-approval', true,
              '["account_no", "cif"]'),
             ('01900000-0000-7000-8000-000000003004'::uuid, 'LOANS', 'LOAN_AGREEMENT', 'Loan agreement',
              'اتفاقية القرض', 'CREDIT', 'CONFIDENTIAL', 'multi-stage-approval', true, '["cif", "contract_ref"]'),
             ('01900000-0000-7000-8000-000000003005'::uuid, 'LOANS', 'COLLATERAL_DEED', 'Collateral deed',
              'سند الضمان', 'COLLATERAL', 'CONFIDENTIAL', 'single-approval', true, '["cif", "collateral_code"]'),
             ('01900000-0000-7000-8000-000000003006'::uuid, 'LOANS', 'SALARY_CERTIFICATE', 'Salary certificate',
              'شهادة راتب', 'INCOME', 'RESTRICTED', null, false, '["cif"]'))
         as d (id, cabinet_code, code, name_en, name_ar, category, classification, process_key, declare_record,
               required)
         join cabinet c on c.code = d.cabinet_code
         join tenant t on t.id = c.tenant_id and t.code = 'BANK1'
on conflict (tenant_id, code) do nothing;

-- Field definitions
insert into field_definition (id, tenant_id, document_type_id, field_key, label_en, label_ar, data_type, mandatory,
                              max_length, default_value, lov_code, searchable, facet, sensitive, display_order,
                              display_row, created_at, created_by, updated_at, updated_by, version_lock)
select gen_random_uuid(), d.tenant_id, d.id, f.field_key, f.label_en, f.label_ar, f.data_type, f.mandatory,
       f.max_length, f.default_value, f.lov_code, f.searchable, f.facet, f.sensitive, f.display_order, f.display_row,
       now(), 'seed', now(), 'seed', 0
from (values
    ('PASSPORT', 'passport_no', 'Passport number', 'رقم جواز السفر', 'TEXT', true, 20, null, null, true, false, false, 1, 1),
    ('PASSPORT', 'nationality', 'Nationality', 'الجنسية', 'LOV', true, null, null, 'NATIONALITY', true, true, false, 2, 1),
    ('PASSPORT', 'issue_date', 'Issue date', 'تاريخ الإصدار', 'DATE', true, null, null, null, true, false, false, 1, 2),
    ('PASSPORT', 'place_of_issue', 'Place of issue', 'مكان الإصدار', 'TEXT', false, 100, null, null, false, false, false, 2, 2),
    ('NATIONAL_ID', 'national_id_no', 'National ID number', 'رقم الهوية الوطنية', 'TEXT', true, 20, null, null, true, false, false, 1, 1),
    ('NATIONAL_ID', 'issue_date', 'Issue date', 'تاريخ الإصدار', 'DATE', true, null, null, null, true, false, false, 2, 1),
    ('ACCOUNT_OPENING_FORM', 'account_type', 'Account type', 'نوع الحساب', 'LOV', true, null, null, 'ACCOUNT_TYPE', true, true, false, 1, 1),
    ('ACCOUNT_OPENING_FORM', 'date_opened', 'Date opened', 'تاريخ الفتح', 'DATE', true, null, null, null, true, false, false, 2, 1),
    ('ACCOUNT_OPENING_FORM', 'officer', 'Relationship officer', 'موظف العلاقة', 'TEXT', false, 100, null, null, true, false, false, 1, 2),
    ('ACCOUNT_OPENING_FORM', 'joint_account', 'Joint account', 'حساب مشترك', 'BOOLEAN', false, null, 'false', null, true, true, false, 2, 2),
    ('LOAN_AGREEMENT', 'facility_type', 'Facility type', 'نوع التسهيل', 'LOV', true, null, null, 'FACILITY_TYPE', true, true, false, 1, 1),
    ('LOAN_AGREEMENT', 'amount', 'Amount', 'المبلغ', 'NUMBER', true, null, null, null, true, false, false, 2, 1),
    ('LOAN_AGREEMENT', 'currency', 'Currency', 'العملة', 'LOV', true, null, 'OMR', 'CURRENCY', true, true, false, 3, 1),
    ('LOAN_AGREEMENT', 'loan_origination_date', 'Origination date', 'تاريخ منح القرض', 'DATE', true, null, null, null, true, false, false, 1, 2),
    ('LOAN_AGREEMENT', 'tenor_months', 'Tenor (months)', 'المدة (بالأشهر)', 'NUMBER', false, null, null, null, false, false, false, 2, 2),
    ('COLLATERAL_DEED', 'collateral_type', 'Collateral type', 'نوع الضمان', 'LOV', true, null, null, 'COLLATERAL_TYPE', true, true, false, 1, 1),
    ('COLLATERAL_DEED', 'valuation_amount', 'Valuation amount', 'قيمة التقييم', 'NUMBER', true, null, null, null, true, false, false, 2, 1),
    ('COLLATERAL_DEED', 'valuation_date', 'Valuation date', 'تاريخ التقييم', 'DATE', true, null, null, null, true, false, false, 1, 2),
    ('COLLATERAL_DEED', 'deed_number', 'Deed number', 'رقم السند', 'TEXT', false, 50, null, null, true, false, false, 2, 2),
    ('SALARY_CERTIFICATE', 'employer', 'Employer', 'جهة العمل', 'TEXT', true, 200, null, null, true, false, false, 1, 1),
    ('SALARY_CERTIFICATE', 'monthly_salary', 'Monthly salary', 'الراتب الشهري', 'NUMBER', true, null, null, null, false, false, true, 2, 1),
    ('SALARY_CERTIFICATE', 'issue_date', 'Issue date', 'تاريخ الإصدار', 'DATE', true, null, null, null, true, false, false, 1, 2))
    as f (type_code, field_key, label_en, label_ar, data_type, mandatory, max_length, default_value, lov_code,
          searchable, facet, sensitive, display_order, display_row)
         join document_type d on d.code = f.type_code
         join tenant t on t.id = d.tenant_id and t.code = 'BANK1'
on conflict (tenant_id, document_type_id, field_key) do nothing;
