-- F2-04: extend the analytical parameter catalog with two controls requested for the
-- fermentative follow-up (CONTROL panel).
--
--  * MALIC_ACID         — total malic acid (L+D isomers) measured independently from the
--                          existing L_MALIC_ACID entry, which tracks only the L isomer for
--                          the malolactic panel. Keeping both avoids overloading a single
--                          parameter with two different analytical scopes.
--  * TOTAL_ACIDITY_TH2  — total acidity measured by the TH2 titration. Distinct from the
--                          generic TOTAL_ACIDITY because the TH2 method reports values in a
--                          slightly different reference frame; recording them as a separate
--                          parameter preserves traceability of the method (RN section 2).
--
-- Both are added as required parameters of the CONTROL panel ("Control fermentativo"),
-- alongside REDUCING_SUGARS and CONTENT_TEMPERATURE.

insert into parameter (code, name, reference_unit, decimal_places, description) values
    ('MALIC_ACID', 'Ácido málico', 'g/L', 2,
     'Ácido málico total. Complementa al L_MALIC_ACID cuando el equipo no distingue isómeros.'),
    ('TOTAL_ACIDITY_TH2', 'Acidez total TH2', 'g/L como tartárico', 2,
     'Acidez total por valoración TH2. Mantener método y referencia junto al resultado.')
on conflict (code) do nothing;

insert into analysis_panel_parameter (panel_id, parameter_id, required)
select panel.id, parameter.id, true
  from analysis_panel panel
  join parameter on parameter.code in ('MALIC_ACID', 'TOTAL_ACIDITY_TH2')
 where panel.code = 'CONTROL'
on conflict (panel_id, parameter_id) do nothing;