-- Analysis templates (panels) and parameters become manageable from Administración, and each template
-- is assigned to the content categories it is meant for: a must is analysed with the fermentation
-- control, a finished wine with the wine analysis.

alter table parameter add column active boolean not null default true;
alter table analysis_panel add column active boolean not null default true;

-- Parameters keep the order the laboratory reads them in.
alter table analysis_panel_parameter add column position integer not null default 0;
update analysis_panel_parameter pp set position = ordered.rn
  from (select pp2.id, row_number() over (partition by pp2.panel_id order by par.name) as rn
          from analysis_panel_parameter pp2 join parameter par on par.id = pp2.parameter_id) ordered
 where ordered.id = pp.id;

-- The names the laboratory already uses on screen become the stored names (the sample form used to
-- map them by hand: Control, Ampliado, Reducido, Maloláctica).
update analysis_panel set name = 'Ampliado' where code = 'ROUTINE_COMPLETE';
update analysis_panel set name = 'Reducido' where code = 'REDUCED';
update analysis_panel set description = 'Panel reducido de tres parámetros.' where code = 'REDUCED';
-- A catalogue name still in English from V13.
update parameter set name = 'Alcohol probable' where code = 'POTENTIAL_ALCOHOL' and name = 'Potential alcohol';

create table analysis_panel_category (
    panel_id     uuid not null references analysis_panel (id) on delete cascade,
    category_id  uuid not null references internal_category (id) on delete cascade,
    is_default   boolean not null default false,
    primary key (panel_id, category_id)
);
-- A category may offer several templates, but only one is proposed by default.
create unique index uq_panel_category_default on analysis_panel_category (category_id) where is_default;

comment on table analysis_panel_category is
    'Which analysis templates a content category offers; the default one is proposed when a sample is registered.';

-- The finished-wine analysis, as the laboratory worksheet for wine reads it.
insert into analysis_panel (code, name, description)
values ('WINE', 'Análisis de vino', 'Vino terminado: grado, acidez, color, azúcares y conservantes.')
on conflict (code) do nothing;

insert into analysis_panel_parameter (panel_id, parameter_id, required, position)
select p.id, par.id, v.required, v.position
  from analysis_panel p
  join (values
    ('ETHANOL', true, 1), ('REDUCING_SUGARS', false, 2), ('PH', true, 3), ('TOTAL_ACIDITY_TH2', true, 4),
    ('TARTARIC_ACID', false, 5), ('MALIC_ACID', true, 6), ('L_LACTIC_ACID', false, 7), ('VOLATILE_ACIDITY', true, 8),
    ('COLOR_INTENSITY', false, 9), ('ABS_420', false, 10), ('ABS_520', false, 11), ('ABS_620', false, 12),
    ('GLUCONIC_ACID', false, 13), ('GLUCOSE_FRUCTOSE', false, 14), ('TOTAL_ACIDITY', false, 15), ('DENSITY', false, 16),
    ('GLYCEROL', false, 17), ('GLUCOSE', false, 18), ('FRUCTOSE', false, 19), ('CITRIC_ACID', false, 20),
    ('SORBIC_ACID', false, 21), ('DISSOLVED_CO2', false, 22), ('TOTAL_POLYPHENOL_INDEX', false, 23),
    ('CONTENT_TEMPERATURE', false, 24)
  ) as v(code, required, position) on true
  join parameter par on par.code = v.code
 where p.code = 'WINE'
on conflict (panel_id, parameter_id) do nothing;

-- Default assignments: must → fermentation control; wines → wine analysis. The rest stay selectable.
insert into analysis_panel_category (panel_id, category_id, is_default)
select p.id, c.id, (p.code, c.code) in (('CONTROL', 'MUST'), ('WINE', 'RED'), ('WINE', 'WHITE'), ('WINE', 'ROSE'), ('WINE', 'BASE_WINE'))
  from analysis_panel p
  join internal_category c on
       (c.code = 'MUST' and p.code in ('CONTROL', 'ROUTINE_COMPLETE', 'REDUCED'))
    or (c.code in ('RED', 'WHITE', 'ROSE', 'BASE_WINE') and p.code in ('WINE', 'CONSERVATION', 'MALOLACTIC', 'ROUTINE_COMPLETE', 'REDUCED'))
on conflict do nothing;
