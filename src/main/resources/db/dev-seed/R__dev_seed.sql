-- Development-only seed: one user per role. Loaded only by the "dev" and "test" profiles.
-- Password for every user: ChangeMe123!  (same BCrypt hash as the V10 seed)
--
-- center_id is resolved dynamically (oldest row in "center") rather than hardcoded, because
-- a dev database may have been seeded/reset with different center data than the V10 migration.

insert into app_user (id, username, email, full_name, password_hash, center_id, active)
select v.id, v.username, v.email, v.full_name, v.password_hash, (select id from center order by created_at limit 1), true
from (values
  ('00000000-0000-0000-0000-0000000000d1'::uuid, 'admin',       'admin@miriv.local',       'Admin Desarrollo',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK'),
  ('00000000-0000-0000-0000-0000000000d2'::uuid, 'enologo',     'enologo@miriv.local',     'Elena Enóloga',           '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK'),
  ('00000000-0000-0000-0000-0000000000d3'::uuid, 'laboratorio', 'laboratorio@miriv.local', 'Luis Laboratorio',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK'),
  ('00000000-0000-0000-0000-0000000000d4'::uuid, 'operario',    'operario@miriv.local',    'Óscar Operario',          '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK'),
  ('00000000-0000-0000-0000-0000000000d5'::uuid, 'produccion',  'produccion@miriv.local',  'Paula Producción',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK'),
  ('00000000-0000-0000-0000-0000000000d6'::uuid, 'consulta',    'consulta@miriv.local',    'Carmen Consulta',         '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK')
) as v(id, username, email, full_name, password_hash)
on conflict do nothing;

insert into user_profile (user_id, first_name, last_name, job_title)
select id, split_part(full_name, ' ', 1), substr(full_name, length(split_part(full_name, ' ', 1)) + 2), null
from app_user where username in ('admin','enologo','laboratorio','operario','produccion','consulta')
on conflict (user_id) do nothing;

insert into app_user_center (user_id, center_id)
select id, center_id from app_user where username in ('admin','enologo','laboratorio','operario','produccion','consulta')
on conflict do nothing;

insert into app_user_role (user_id, role_id, zone_id)
select u.id, r.id, null from app_user u join role r on r.code = case u.username
    when 'admin' then 'ADMIN' when 'enologo' then 'ENOLOGIST' when 'laboratorio' then 'LABORATORY'
    when 'produccion' then 'PRODUCTION_MANAGER' when 'consulta' then 'VIEWER' end
where u.username in ('admin','enologo','laboratorio','produccion','consulta')
on conflict do nothing;

-- The operator only works in one zone of their center (whichever sorts first by code).
insert into app_user_role (user_id, role_id, zone_id)
select u.id, r.id,
  (select z.id from zone z where z.center_id = u.center_id order by z.code limit 1)
from app_user u, role r
where u.username = 'operario' and r.code = 'CELLAR_OPERATOR'
on conflict do nothing;

-- F1C-01: the laboratory user also gets ANALYSIS_VALIDATE individually.
insert into app_user_permission_grant (user_id, permission_id, zone_id, granted_by_id, reason)
select u.id, p.id, null, null, 'Semilla de desarrollo'
from app_user u, permission p
where u.username = 'laboratorio' and p.code = 'ANALYSIS_VALIDATE'
on conflict do nothing;