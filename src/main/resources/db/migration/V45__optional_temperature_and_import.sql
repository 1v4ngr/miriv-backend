-- Temperature is measured again in routine analyses, but not in every one: it joins the panels as an
-- OPTIONAL parameter, so an analysis without it can still be validated (V20 had removed it from CONTROL
-- precisely because being required blocked validation).
insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, false
  from analysis_panel p
  join parameter par on par.code = 'CONTENT_TEMPERATURE'
 where p.code in ('CONTROL', 'ROUTINE_COMPLETE', 'MALOLACTIC', 'CONSERVATION')
on conflict (panel_id, parameter_id) do update set required = false;

-- Importing a sheet of analyser readings creates the samples and their results in one go.
insert into role_permission (role_id, permission_id)
select r.id, p.id
  from role r
  join permission p on p.code = 'RESULT_IMPORT'
 where r.code = 'ENOLOGIST'
on conflict do nothing;
