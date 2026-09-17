alter table analysis add column method_description varchar(200);
alter table analysis add column equipment varchar(120);
alter table analysis add column observations varchar(1000);
alter table analysis add column validation_note varchar(1000);

insert into parameter (code, name, reference_unit, decimal_places, description)
values ('POTENTIAL_ALCOHOL', 'Potential alcohol', '% vol.', 2, 'Estimate based on sugar; not measured ethanol.');

insert into analysis_panel (code, name, description)
values ('REDUCED', 'Reduced', 'Reduced three-parameter laboratory panel.');

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select panel.id, parameter.id, true
from analysis_panel panel join parameter on parameter.code in ('PH', 'DENSITY', 'VOLATILE_ACIDITY')
where panel.code = 'REDUCED';

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select panel.id, parameter.id, true
from analysis_panel panel join parameter on parameter.code in ('REDUCING_SUGARS', 'CONTENT_TEMPERATURE')
where panel.code = 'CONTROL'
on conflict (panel_id, parameter_id) do nothing;

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select panel.id, parameter.id, true
from analysis_panel panel join parameter on parameter.code in ('VOLATILE_ACIDITY', 'CONTENT_TEMPERATURE')
where panel.code = 'MALOLACTIC'
on conflict (panel_id, parameter_id) do nothing;

delete from analysis_panel_parameter
where panel_id = (select id from analysis_panel where code = 'ROUTINE_COMPLETE')
  and parameter_id in (select id from parameter where code in ('ETHANOL', 'GLUCOSE_FRUCTOSE', 'TOTAL_SO2'));
insert into analysis_panel_parameter (panel_id, parameter_id, required)
select panel.id, parameter.id, true
from analysis_panel panel join parameter on parameter.code in ('DENSITY', 'CONTENT_TEMPERATURE', 'POTENTIAL_ALCOHOL')
where panel.code = 'ROUTINE_COMPLETE'
on conflict (panel_id, parameter_id) do nothing;
