-- CONTROL panel no longer requires temperature.
-- Other panels (MALOLACTIC, ROUTINE_COMPLETE) keep CONTENT_TEMPERATURE.

delete from analysis_panel_parameter
where panel_id = (select id from analysis_panel where code = 'CONTROL')
  and parameter_id = (select id from parameter where code = 'CONTENT_TEMPERATURE');
