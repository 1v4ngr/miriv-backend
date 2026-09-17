-- Centers, zones, roles, users and audit log.
-- gen_random_uuid() is built in to PostgreSQL core since version 13; no extension required.

create table center (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(30) not null unique,
    name            varchar(120) not null,
    created_at      timestamptz not null default now()
);

create table zone (
    id              uuid primary key default gen_random_uuid(),
    center_id       uuid not null references center (id),
    code            varchar(30) not null,
    name            varchar(120) not null,
    created_at      timestamptz not null default now(),
    constraint uq_zone_center_code unique (center_id, code)
);

create table role (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    description     varchar(500)
);

comment on table role is 'RF-USR-01: individual accounts may hold several roles at once.';

create table app_user (
    id              uuid primary key default gen_random_uuid(),
    username        varchar(120) not null unique,
    email           varchar(200) not null unique,
    full_name       varchar(200) not null,
    password_hash   varchar(200) not null,
    center_id       uuid references center (id),
    active          boolean not null default true,
    created_at      timestamptz not null default now(),
    deactivated_at  timestamptz
);

create table app_user_role (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references app_user (id),
    role_id         uuid not null references role (id),
    zone_id         uuid references zone (id)
);

comment on table app_user_role is 'RF-USR-02: role assignment may be scoped to a single zone; a null zone means every zone in the center.';

create index ix_app_user_role_user on app_user_role (user_id);

-- A null zone_id represents "every zone"; the sentinel keeps the uniqueness check simple.
create unique index uq_app_user_role_scope on app_user_role (
    user_id, role_id, coalesce(zone_id, '00000000-0000-0000-0000-000000000000')
);

-- RNF-07: append-only audit trail shared by every module.
create table audit_log (
    id              uuid primary key default gen_random_uuid(),
    entity_name     varchar(80) not null,
    entity_id       uuid not null,
    action          varchar(20) not null,
    previous_value  jsonb,
    new_value       jsonb,
    author_id       uuid references app_user (id),
    reason          varchar(1000),
    created_at      timestamptz not null default now()
);

create index ix_audit_log_entity on audit_log (entity_name, entity_id);
create index ix_audit_log_created_at on audit_log (created_at);
