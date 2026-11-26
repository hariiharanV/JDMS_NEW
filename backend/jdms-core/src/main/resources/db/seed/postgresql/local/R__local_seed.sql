-- Local development data only (profile "local"); matches the users in deploy/keycloak/jdms-local-realm.json.
-- Re-runs when this file changes; existing rows are left alone.

insert into tenant (id, code, name, status, default_locale, languages, settings,
                    created_at, created_by, updated_at, updated_by, version_lock)
values ('01900000-0000-7000-8000-000000000001', 'BANK1', 'Bank One (local)', 'ACTIVE', 'en', 'en,ar', '{}',
        now(), 'seed', now(), 'seed', 0),
       ('01900000-0000-7000-8000-000000000002', 'BANK2', 'Bank Two (local)', 'ACTIVE', 'ar', 'en,ar', '{}',
        now(), 'seed', now(), 'seed', 0)
on conflict (code) do nothing;

insert into branch (id, tenant_id, code, name, region, created_at, created_by, updated_at, updated_by, version_lock)
select b.id, t.id, b.code, b.name, b.region, now(), 'seed', now(), 'seed', 0
from (values ('01900000-0000-7000-8000-000000000101'::uuid, 'BANK1', '001', 'Head Office', 'Central'),
             ('01900000-0000-7000-8000-000000000102'::uuid, 'BANK1', '002', 'Main Street', 'Central'),
             ('01900000-0000-7000-8000-000000000103'::uuid, 'BANK1', '003', 'Airport', 'North'),
             ('01900000-0000-7000-8000-000000000201'::uuid, 'BANK2', '001', 'Head Office', 'Central'))
         as b (id, tenant_code, code, name, region)
         join tenant t on t.code = b.tenant_code
on conflict (tenant_id, code) do nothing;
