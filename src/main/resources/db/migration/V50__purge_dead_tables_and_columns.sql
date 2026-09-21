-- V50 — Limpieza de tablas y columnas que quedaron huérfanas tras V49.
-- Inspección contra el backend Java: ninguna clase de producción (fuera de
-- CenterPurgeService que sólo las borra) las referencia para SELECT / INSERT /
-- UPDATE. Sus valores se pueden gestionar vía /api/catalogs/* pero no afectan a
-- ninguna regla, página ni filtro de la app.
--
-- Tablas enteras a eliminar:
--   * color, product_type              (catálogos administrativos sin uso real)
--   * plan_exception, plan_phase_criterion (introducidas en V7, nunca consultadas)
--   * deposit_capacity_adjustment, lot_origin_line (V3, sólo borradas por el purge)
--
-- Columnas dentro de tablas que se conservan (no afectan a nada que ya esté vivo):
--   * deposit.observations             (setter expuesto pero nunca llamado)
--   * content_unit_lineage.note
--   * lot.color_id, content_unit.color_id, elaboration_plan.destination_id? -> no, destination sí se usa
--
-- Enums huérfanos tras V49:
--   * incident_resolution (sólo lo usaba incident.resolution)
--   * operation_type / operation_status (ya en V49 pero se reitera con cascade)
--
-- Orden: primero columnas FK que apuntan a tablas a eliminar; luego tablas; luego enums.

-- 1) Quitar columnas FK hacia tablas que vamos a borrar.
alter table lot drop column if exists color_id;
alter table content_unit drop column if exists color_id;

-- 2) Columnas muertas que no se leen en producción.
alter table deposit drop column if exists observations;
alter table content_unit_lineage drop column if exists note;

-- 3) Tablas de catálogo administrativo nunca consultadas.
drop table if exists color cascade;
drop table if exists product_type cascade;

-- 4) Tablas de plan declaradas en V7 pero nunca creadas/consultadas por PlanService.
drop table if exists plan_exception cascade;
drop table if exists plan_phase_criterion cascade;

-- 5) Tablas de historial declaradas pero sólo borradas en el purge.
drop table if exists deposit_capacity_adjustment cascade;
drop table if exists lot_origin_line cascade;

-- 6) Enum huérfano tras V49.
drop type if exists incident_resolution cascade;
drop type if exists operation_type cascade;
drop type if exists operation_status cascade;