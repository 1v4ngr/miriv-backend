-- RF-ANA: a sample can be registered against the wrong tank. Reassigning it moves the analysis to the
-- content that actually occupied the chosen deposit at the sampling time, so curves and alerts follow
-- the right wine. Reserved for enologists (SUPER_ADMIN already holds every permission).
insert into permission (code, group_name, description, grantable) values
  ('SAMPLE_REASSIGN', 'Laboratorio', 'Reasignar una muestra al depósito correcto con motivo', true)
on conflict (code) do nothing;

insert into role_permission (role_id, permission_id)
select r.id, p.id
  from role r
  join permission p on p.code = 'SAMPLE_REASSIGN'
 where r.code in ('ENOLOGIST', 'SUPER_ADMIN')
on conflict do nothing;
