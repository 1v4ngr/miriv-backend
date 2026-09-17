-- Reference data required before the application can be used: roles, one center/zone pair,
-- catalogs, analytical parameters and panels. Business data (deposits, lots, samples...) is
-- intentionally not seeded here; it belongs to the operational history of a real cooperative.

insert into role (code, name, description) values
    ('ENOLOGIST', 'Enólogo responsable', 'Defines plans and rules, validates states, approves actions and closes incidents.'),
    ('LABORATORY', 'Laboratorio', 'Creates samples, enters or imports results, validates analyses when permitted.'),
    ('CELLAR_OPERATOR', 'Operario de bodega', 'Reads instructions, records controls and executes authorised movements or tasks.'),
    ('PRODUCTION_MANAGER', 'Responsable de producción', 'Reviews capacity, assigns tasks and reviews planning.'),
    ('ADMIN', 'Administrador', 'Manages users, catalogs, integrations and technical configuration.'),
    ('VIEWER', 'Consulta', 'Read-only access within the authorised scope.');

insert into center (id, code, name) values
    ('00000000-0000-0000-0000-000000000001', 'CENTRO-NORTE', 'Centro Norte');

insert into zone (center_id, code, name) values
    ('00000000-0000-0000-0000-000000000001', 'NAVE-A', 'Nave A'),
    ('00000000-0000-0000-0000-000000000001', 'NAVE-B', 'Nave B');

-- Seed enologist account. Password is "ChangeMe123!" — rotate before any non-local use.
insert into app_user (id, username, email, full_name, password_hash, center_id, active) values
    ('00000000-0000-0000-0000-0000000000a1', 'm.solana', 'm.solana@miriv.coop', 'María Solana',
     '$2b$10$c7zcOqm3CDT4CAYvOXXie.EFSMZwllpTYUxUF9y3flEfGbu.ShYIK',
     '00000000-0000-0000-0000-000000000001', true);

insert into app_user_role (user_id, role_id)
    select '00000000-0000-0000-0000-0000000000a1', id from role where code = 'ENOLOGIST';

-- RF-CAT-01 catalogs.
insert into product_type (code, name) values
    ('MUST', 'Mosto'),
    ('MUST_FERMENTING', 'Mosto en fermentación'),
    ('WINE', 'Vino');

insert into color (code, name) values
    ('WHITE', 'Blanco'),
    ('ROSE', 'Rosado'),
    ('RED', 'Tinto'),
    ('UNDETERMINED', 'Sin determinar');

insert into destination (code, name) values
    ('STILL_WINE', 'Vino tranquilo'),
    ('CAVA_BASE', 'Base para cava'),
    ('PENDING', 'Pendiente'),
    ('OTHER', 'Otros');

insert into internal_category (code, name, description) values
    ('RED', 'Tinto', 'Vino tinto tranquilo'),
    ('WHITE', 'Blanco', 'Vino blanco tranquilo'),
    ('ROSE', 'Rosado', 'Vino rosado'),
    ('MUST', 'Mosto', 'Mosto sin fermentar o en fermentación'),
    ('BASE_WINE', 'Vino base', 'Base destinada a cava u otra elaboración posterior');

insert into variety (code, name) values
    ('TEMPRANILLO', 'Tempranillo'),
    ('VERDEJO', 'Verdejo'),
    ('GARNACHA', 'Garnacha');

-- Section 6: analytical parameter catalog (canonical reference unit for comparison).
insert into parameter (code, name, reference_unit, decimal_places, description) values
    ('ETHANOL', 'Etanol', '% vol.', 2, 'Identify method and reference temperature when relevant.'),
    ('GLUCOSE', 'Glucosa', 'g/L', 2, 'Individual result if the equipment provides it.'),
    ('FRUCTOSE', 'Fructosa', 'g/L', 2, 'Individual result if the equipment provides it.'),
    ('GLUCOSE_FRUCTOSE', 'Glucosa más fructosa', 'g/L', 2, 'Distinguish joint measurement from calculated sum.'),
    ('TOTAL_ACIDITY', 'Acidez total', 'g/L como tartárico', 2, 'Other references admitted with documented conversion.'),
    ('PH', 'pH', '', 2, 'Never averaged to estimate a mix (RN section 3).'),
    ('VOLATILE_ACIDITY', 'Acidez volátil', 'g/L como acético', 2, 'Keep reference and method.'),
    ('DENSITY', 'Densidad', 'g/mL', 4, 'Keep measurement temperature and correction.'),
    ('BRIX_BAUME', 'Grados Brix o Baumé', '°Brix', 1, 'Separate magnitude; record instrument.'),
    ('REDUCING_SUGARS', 'Azúcares reductores', 'g/L', 2, 'Does not automatically replace glucose plus fructose.'),
    ('L_MALIC_ACID', 'Ácido L málico', 'g/L', 2, 'Malolactic follow-up.'),
    ('L_LACTIC_ACID', 'Ácido L láctico', 'g/L', 2, 'Interpretive support; distinguish other isomers.'),
    ('FREE_SO2', 'SO2 libre', 'mg/L', 1, 'Interpretation depends on plan and method.'),
    ('TOTAL_SO2', 'SO2 total', 'mg/L', 1, 'Not equivalent to free SO2.'),
    ('YAN', 'YAN', 'mg N/L', 0, 'Record components and method; relevant on initial must.'),
    ('CONTENT_TEMPERATURE', 'Temperatura del contenido', '°C', 1, 'Point, depth and origin of the reading.'),
    ('SAMPLE_TEMPERATURE', 'Temperatura de muestra', '°C', 1, 'Distinct from deposit temperature.'),
    ('DISSOLVED_CO2', 'CO2 disuelto', 'mg/L', 1, 'Not to be confused with ambient CO2.'),
    ('GLUCONIC_ACID', 'Ácido glucónico', 'g/L', 2, 'Complementary depending on reception and protocol.'),
    ('TURBIDITY', 'Turbidez', 'NTU', 1, 'Optional depending on phase and equipment.'),
    ('DISSOLVED_OXYGEN', 'Oxígeno disuelto', 'mg/L', 2, 'Optional; sampling context is essential.');

-- Section 7: analytical panels.
insert into analysis_panel (code, name, description) values
    ('CONTROL', 'Control fermentativo', 'Minimal panel to follow an active fermentation.'),
    ('ROUTINE_COMPLETE', 'Rutinario completo', 'Full routine panel.'),
    ('MALOLACTIC', 'Maloláctica', 'Malolactic follow-up panel.'),
    ('CONSERVATION', 'Conservación', 'Panel for stored, stabilised wine.');

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, true
from analysis_panel p
join parameter par on par.code in ('VOLATILE_ACIDITY', 'PH', 'DENSITY')
where p.code = 'CONTROL';

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, true
from analysis_panel p
join parameter par on par.code in (
    'ETHANOL', 'GLUCOSE_FRUCTOSE', 'TOTAL_ACIDITY', 'PH', 'VOLATILE_ACIDITY',
    'FREE_SO2', 'TOTAL_SO2', 'L_MALIC_ACID'
)
where p.code = 'ROUTINE_COMPLETE';

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, true
from analysis_panel p
join parameter par on par.code in ('L_MALIC_ACID', 'L_LACTIC_ACID', 'PH')
where p.code = 'MALOLACTIC';

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, true
from analysis_panel p
join parameter par on par.code in ('FREE_SO2', 'TOTAL_SO2', 'VOLATILE_ACIDITY', 'PH')
where p.code = 'CONSERVATION';
