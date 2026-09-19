-- Super administrator: holds every permission (including ones added later, resolved in
-- AppUserDetailsService) and is the only role that may grant or revoke SUPER_ADMIN or change
-- another super administrator's account. The account that receives it is set at startup by
-- SuperAdminBootstrap (app.security.super-admin-email).

insert into role (code, name, description) values
  ('SUPER_ADMIN', 'Superadministrador',
   'Puede hacer todo y conceder cualquier rol o permiso, incluido el de superadministrador.')
on conflict (code) do nothing;

-- Explicit rows so listings and reports show the full set; new permissions are covered at runtime.
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r cross join permission p
 where r.code = 'SUPER_ADMIN'
on conflict do nothing;
