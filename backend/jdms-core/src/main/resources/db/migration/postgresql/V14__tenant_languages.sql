-- ADR-0023: Arabic is optional. Each tenant lists the languages its screens offer ('en' or 'en,ar'; English is
-- always on); tenant admins change it through maker-checker. New tenants start with English only. Tenants that
-- exist already had Arabic, so they keep it. Arabic names of document types, fields and list values may be left
-- out; screens then show the English name.

alter table tenant add column languages varchar(50) default 'en' not null;
update tenant set languages = 'en,ar';

alter table document_type alter column name_ar drop not null;
alter table field_definition alter column label_ar drop not null;
alter table lov_value alter column label_ar drop not null;
