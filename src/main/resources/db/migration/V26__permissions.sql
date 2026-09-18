-- Section 2 / RF-USR-01..02: fine-grained permissions grouped by role, plus individual grants.

create table permission (
    id          uuid primary key default gen_random_uuid(),
    code        varchar(40) not null unique,
    group_name  varchar(40) not null,
    description varchar(300) not null,
    grantable   boolean not null default false
);

comment on column permission.grantable is 'True when an administrator may grant this permission to an individual user outside their roles.';

create table role_permission (
    role_id       uuid not null references role (id),
    permission_id uuid not null references permission (id),
    primary key (role_id, permission_id)
);

create table app_user_permission_grant (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references app_user (id),
    permission_id uuid not null references permission (id),
    zone_id       uuid references zone (id),
    granted_by_id uuid references app_user (id),
    reason        varchar(1000) not null,
    valid_from    timestamptz not null default now(),
    valid_until   timestamptz,
    created_at    timestamptz not null default now()
);

create unique index uq_user_permission_grant on app_user_permission_grant (
    user_id, permission_id, coalesce(zone_id, '00000000-0000-0000-0000-000000000000')
);

insert into permission (code, group_name, description, grantable) values
  ('DEPOSIT_MANAGE',       'Bodega',       'Alta, edición, capacidad, mantenimiento y desactivación de depósitos', false),
  ('DEPOSIT_CLEANING',     'Bodega',       'Iniciar y cerrar limpiezas e inspecciones', false),
  ('LOT_MANAGE',           'Bodega',       'Alta de lote y entrada, edición, archivar y reabrir', false),
  ('MOVEMENT_REGISTER',    'Bodega',       'Registrar y ejecutar movimientos', false),
  ('MOVEMENT_PLAN',        'Bodega',       'Guardar y cancelar movimientos previstos', false),
  ('MIXTURE_AUTHORIZE',    'Bodega',       'Autorizar que un movimiento a depósito ocupado sea una mezcla', true),
  ('CONTENT_CORRECT',      'Bodega',       'Retirar contenido por error de registro y ajustes sin traslado', false),
  ('SAMPLE_REGISTER',      'Laboratorio',  'Registrar tomas de muestra', false),
  ('RESULT_ENTER',         'Laboratorio',  'Introducir resultados y enviarlos a validación', true),
  ('RESULT_IMPORT',        'Laboratorio',  'Importar ficheros de análisis', false),
  ('ANALYSIS_VALIDATE',    'Laboratorio',  'Validar análisis', true),
  ('RESULT_CORRECT',       'Laboratorio',  'Corregir resultados con motivo', true),
  ('ANALYSIS_INVALIDATE',  'Laboratorio',  'Invalidar análisis con motivo', false),
  ('STATE_CONFIRM',        'Elaboración',  'Confirmar o rechazar estados fermentativos', false),
  ('PLAN_EDIT',            'Elaboración',  'Crear y editar planes y plantillas en borrador', false),
  ('PLAN_APPROVE',         'Elaboración',  'Aprobar versiones de plan y asignarlas', false),
  ('PLAN_EXCEPTION',       'Elaboración',  'Excepciones de plan por unidad', false),
  ('RULE_EDIT',            'Elaboración',  'Crear y editar reglas y recomendaciones en borrador', true),
  ('RULE_APPROVE',         'Elaboración',  'Aprobar, activar y desactivar reglas y protocolos', false),
  ('OPERATION_PLAN',       'Operaciones',  'Decidir y planificar operaciones enológicas', false),
  ('OPERATION_EXECUTE',    'Operaciones',  'Registrar operaciones realizadas', false),
  ('INCIDENT_ACKNOWLEDGE', 'Incidencias',  'Reconocer avisos', false),
  ('INCIDENT_ASSIGN',      'Incidencias',  'Asignar y reasignar incidencias', false),
  ('INCIDENT_SILENCE',     'Incidencias',  'Silenciar y reactivar notificaciones de una incidencia', false),
  ('INCIDENT_CLOSE',       'Incidencias',  'Resolver o descartar incidencias con motivo', false),
  ('TASK_CREATE',          'Tareas',       'Crear tareas', false),
  ('TASK_ASSIGN',          'Tareas',       'Asignar, reasignar y cambiar vencimientos', false),
  ('TASK_EXECUTE_OWN',     'Tareas',       'Iniciar y completar tareas propias', false),
  ('TASK_EXECUTE_ANY',     'Tareas',       'Iniciar y completar tareas de otras personas', false),
  ('TASK_CANCEL',          'Tareas',       'Cancelar tareas con motivo', false),
  ('REPORT_EXPORT',        'Informes',     'Generar informes y exportar datos', true),
  ('USER_MANAGE',          'Administración','Usuarios, roles y concesiones', false),
  ('ORG_MANAGE',           'Administración','Centros, zonas, laboratorios y configuración general', false),
  ('CATALOG_MANAGE',       'Administración','Catálogos de producto, variedad, destino y color', false),
  ('LAB_CATALOG_MANAGE',   'Administración','Parámetros, métodos y paneles de análisis', true),
  ('INTEGRATION_MANAGE',   'Administración','Sensores, equipos e integraciones', true),
  ('AUDIT_READ',           'Administración','Consultar la auditoría completa', true);

insert into role_permission (role_id, permission_id)
select r.id, p.id from (values
  ('ENOLOGIST','DEPOSIT_MANAGE'),('ENOLOGIST','DEPOSIT_CLEANING'),('ENOLOGIST','LOT_MANAGE'),
  ('ENOLOGIST','MOVEMENT_REGISTER'),('ENOLOGIST','MOVEMENT_PLAN'),('ENOLOGIST','MIXTURE_AUTHORIZE'),
  ('ENOLOGIST','CONTENT_CORRECT'),('ENOLOGIST','SAMPLE_REGISTER'),('ENOLOGIST','ANALYSIS_INVALIDATE'),
  ('ENOLOGIST','STATE_CONFIRM'),('ENOLOGIST','PLAN_EDIT'),('ENOLOGIST','PLAN_APPROVE'),('ENOLOGIST','PLAN_EXCEPTION'),
  ('ENOLOGIST','RULE_EDIT'),('ENOLOGIST','RULE_APPROVE'),('ENOLOGIST','OPERATION_PLAN'),('ENOLOGIST','OPERATION_EXECUTE'),
  ('ENOLOGIST','INCIDENT_ACKNOWLEDGE'),('ENOLOGIST','INCIDENT_ASSIGN'),('ENOLOGIST','INCIDENT_SILENCE'),('ENOLOGIST','INCIDENT_CLOSE'),
  ('ENOLOGIST','TASK_CREATE'),('ENOLOGIST','TASK_ASSIGN'),('ENOLOGIST','TASK_EXECUTE_OWN'),('ENOLOGIST','TASK_EXECUTE_ANY'),
  ('ENOLOGIST','TASK_CANCEL'),('ENOLOGIST','REPORT_EXPORT'),('ENOLOGIST','AUDIT_READ'),

  ('LABORATORY','SAMPLE_REGISTER'),('LABORATORY','RESULT_ENTER'),('LABORATORY','RESULT_IMPORT'),
  ('LABORATORY','RESULT_CORRECT'),('LABORATORY','ANALYSIS_INVALIDATE'),('LABORATORY','INCIDENT_ACKNOWLEDGE'),
  ('LABORATORY','TASK_EXECUTE_OWN'),('LABORATORY','REPORT_EXPORT'),

  ('CELLAR_OPERATOR','DEPOSIT_CLEANING'),('CELLAR_OPERATOR','MOVEMENT_REGISTER'),('CELLAR_OPERATOR','SAMPLE_REGISTER'),
  ('CELLAR_OPERATOR','OPERATION_EXECUTE'),('CELLAR_OPERATOR','INCIDENT_ACKNOWLEDGE'),('CELLAR_OPERATOR','TASK_EXECUTE_OWN'),

  ('PRODUCTION_MANAGER','DEPOSIT_MANAGE'),('PRODUCTION_MANAGER','DEPOSIT_CLEANING'),('PRODUCTION_MANAGER','LOT_MANAGE'),
  ('PRODUCTION_MANAGER','MOVEMENT_REGISTER'),('PRODUCTION_MANAGER','MOVEMENT_PLAN'),('PRODUCTION_MANAGER','MIXTURE_AUTHORIZE'),
  ('PRODUCTION_MANAGER','CONTENT_CORRECT'),('PRODUCTION_MANAGER','OPERATION_EXECUTE'),('PRODUCTION_MANAGER','INCIDENT_ACKNOWLEDGE'),
  ('PRODUCTION_MANAGER','INCIDENT_ASSIGN'),('PRODUCTION_MANAGER','TASK_CREATE'),('PRODUCTION_MANAGER','TASK_ASSIGN'),
  ('PRODUCTION_MANAGER','TASK_EXECUTE_OWN'),('PRODUCTION_MANAGER','TASK_EXECUTE_ANY'),('PRODUCTION_MANAGER','TASK_CANCEL'),
  ('PRODUCTION_MANAGER','REPORT_EXPORT'),

  ('ADMIN','DEPOSIT_MANAGE'),('ADMIN','USER_MANAGE'),('ADMIN','ORG_MANAGE'),('ADMIN','CATALOG_MANAGE'),
  ('ADMIN','LAB_CATALOG_MANAGE'),('ADMIN','INTEGRATION_MANAGE'),('ADMIN','AUDIT_READ')
) as m(role_code, permission_code)
join role r on r.code = m.role_code
join permission p on p.code = m.permission_code;