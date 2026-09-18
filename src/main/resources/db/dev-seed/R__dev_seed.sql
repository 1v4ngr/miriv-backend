-- Development-only seed: one user per role. Loaded only by the "dev" and "test" profiles.
-- Password for every user: ChangeMe123!  (same BCrypt hash as the V10 seed)

insert into app_user (id, username, email, full_name, password_hash, center_id, active) values
  ('00000000-0000-0000-0000-0000000000d1', 'admin',       'admin@miriv.local',       'Admin Desarrollo',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true),
  ('00000000-0000-0000-0000-0000000000d2', 'enologo',     'enologo@miriv.local',     'Elena Enóloga',           '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true),
  ('00000000-0000-0000-0000-0000000000d3', 'laboratorio', 'laboratorio@miriv.local', 'Luis Laboratorio',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true),
  ('00000000-0000-0000-0000-0000000000d4', 'operario',    'operario@miriv.local',    'Óscar Operario',          '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true),
  ('00000000-0000-0000-0000-0000000000d5', 'produccion',  'produccion@miriv.local',  'Paula Producción',        '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true),
  ('00000000-0000-0000-0000-0000000000d6', 'consulta',    'consulta@miriv.local',    'Carmen Consulta',         '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK', '00000000-0000-0000-0000-000000000001', true)
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

-- The operator only works in zone NAVE-A.
insert into app_user_role (user_id, role_id, zone_id)
select u.id, r.id, z.id from app_user u, role r, zone z
where u.username = 'operario' and r.code = 'CELLAR_OPERATOR'
  and z.code = 'NAVE-A' and z.center_id = '00000000-0000-0000-0000-000000000001'
on conflict do nothing;

-- F1C-01: the laboratory user also gets ANALYSIS_VALIDATE individually.
insert into app_user_permission_grant (user_id, permission_id, zone_id, granted_by_id, reason)
select u.id, p.id, null, null, 'Semilla de desarrollo'
from app_user u, permission p
where u.username = 'laboratorio' and p.code = 'ANALYSIS_VALIDATE'
on conflict do nothing;