-- 1) Fermentation states are codes everywhere. Confirmations were stored as Spanish labels ("Activa"),
--    while alert rules (V43) and analytical targets (V29) are scoped by codes (ACTIVE…): once a state
--    was confirmed, rules and phase-specific targets silently stopped applying to that wine.
create temporary table fermentation_label_code (label varchar(60) primary key, code varchar(60)) on commit drop;
insert into fermentation_label_code values
  ('No iniciada', 'NOT_STARTED'), ('Activa', 'ACTIVE'), ('Lenta', 'SLOW'),
  ('Sospecha de parada', 'SUSPECTED_STOP'), ('Finalizada', 'FINISHED'), ('No prevista', 'NOT_EXPECTED');

update fermentation_state s set confirmed_status = m.code
  from fermentation_label_code m where s.confirmed_status = m.label;
update fermentation_state s set estimated_status = m.code
  from fermentation_label_code m where s.estimated_status = m.label;
update fermentation_state_review r set decision = m.code
  from fermentation_label_code m where r.decision = m.label;
update fermentation_state_review r set previous_status = m.code
  from fermentation_label_code m where r.previous_status = m.label;
update parameter_target t set phase = m.code
  from fermentation_label_code m where t.phase = m.label;
update alert_rule r set phases = (
    select coalesce(jsonb_agg(coalesce(m.code, p.value)), '[]'::jsonb)
      from jsonb_array_elements_text(r.phases) p(value)
      left join fermentation_label_code m on m.label = p.value)
 where exists (select 1 from jsonb_array_elements_text(r.phases) p(value)
               join fermentation_label_code m on m.label = p.value);

-- 2) Parameters of the finished-wine analysis that the catalogue lacked (analyser worksheet for wine).
insert into parameter (code, name, reference_unit, decimal_places, description) values
  ('TARTARIC_ACID', 'Ácido tartárico', 'g/L', 2, 'Tartaric acid; relevant for tartaric stabilisation.'),
  ('CITRIC_ACID', 'Ácido cítrico', 'g/L', 2, 'Citric acid; check against legal limits when added.'),
  ('SORBIC_ACID', 'Ácido sórbico', 'mg/L', 1, 'Sorbic acid; preservative, check against legal limits.'),
  ('GLYCEROL', 'Glicerol', 'g/L', 2, 'Glycerol; fermentation by-product linked to body.'),
  ('COLOR_INTENSITY', 'Intensidad colorante', '', 2, 'Colour intensity (IC = A420 + A520 + A620).'),
  ('ABS_420', 'Absorbancia 420 nm', 'UA', 3, 'Absorbance at 420 nm (yellow component).'),
  ('ABS_520', 'Absorbancia 520 nm', 'UA', 3, 'Absorbance at 520 nm (red component).'),
  ('ABS_620', 'Absorbancia 620 nm', 'UA', 3, 'Absorbance at 620 nm (blue component).'),
  ('TOTAL_POLYPHENOL_INDEX', 'Índice de polifenoles totales', '', 1, 'Total polyphenol index (IPT, absorbance at 280 nm).')
on conflict (code) do nothing;
