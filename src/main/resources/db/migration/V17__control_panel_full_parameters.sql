-- Aligns the CONTROL panel with the cooperative's actual must-analyser worksheet
-- (Etanol, Glu+Fruc, Aci. Total, pH, Aci. Vola., Densidad, Az. Reduct., CO2 disuelto).
insert into analysis_panel_parameter (panel_id, parameter_id, required)
select p.id, par.id, true
from analysis_panel p
join parameter par on par.code in ('ETHANOL', 'GLUCOSE_FRUCTOSE', 'TOTAL_ACIDITY', 'DISSOLVED_CO2')
where p.code = 'CONTROL'
on conflict (panel_id, parameter_id) do nothing;
