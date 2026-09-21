-- V49 — Borrado total de Actividad, Incidencias, Tareas y Elaboración.
-- Elimina las tablas y tipos asociados a las áreas que ya no se utilizan en la app:
--   * task / task_execution / task_status / task_priority     (Tareas)
--   * incident / incident_event / incident_evidence / incident_status   (Incidencias)
--   * operation / operation_addition / operation_status / operation_type   (Actividad)
--   * rule / rule_version / rule_status / alert_priority      (catálogo de reglas RF-EVA-08)
-- Las tablas que SÍ se usan (elaboration_plan / plan_version / plan_phase_criterion /
-- plan_exception / fermentation_state / alert_rule / alert_acknowledgement / audit_log)
-- permanecen; las FKs huérfanas que apuntan a tablas a eliminar se quitan antes del DROP.

-- 1) Desligar las tablas que se conservan de las que se van a borrar.
--    blend_simulation.task_id -> task
--    fermentation_state.rule_version_id -> rule_version
alter table blend_simulation drop column if exists task_id;
alter table fermentation_state drop column if exists rule_version_id;

-- 2) task: borra primero sus tablas hijas, luego la tabla y por último el tipo.
drop table if exists task_execution cascade;
drop table if exists task cascade;
drop type if exists task_status cascade;
drop type if exists task_priority cascade;

-- 3) incident y sus tablas/eventos hijos.
drop table if exists incident_evidence cascade;
drop table if exists incident_event cascade;
drop table if exists incident cascade;
drop type if exists incident_status cascade;

-- 4) operation / operation_addition.
drop table if exists operation_addition cascade;
drop table if exists operation cascade;
drop type if exists operation_status cascade;
drop type if exists operation_type cascade;

-- 5) rule / rule_version.
drop table if exists rule_version cascade;
drop table if exists rule cascade;
drop type if exists rule_status cascade;
drop type if exists alert_priority cascade;