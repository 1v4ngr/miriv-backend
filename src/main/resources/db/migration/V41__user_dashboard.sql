-- F6-01: personal, server-side dashboards for the tracking section.
create table user_dashboard (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references app_user (id) on delete cascade,
    name           varchar(80) not null,
    position       integer not null default 0,
    schema_version integer not null default 1,
    layouts        jsonb not null default '{}'::jsonb,
    widgets        jsonb not null default '[]'::jsonb,
    is_default     boolean not null default false,
    version        integer not null default 0,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

comment on column user_dashboard.layouts is 'react-grid-layout layouts per breakpoint: {"lg":[{"i","x","y","w","h"}],"md":[…],"sm":[…]}';
comment on column user_dashboard.widgets is 'Array of widget configs [{"id","type","title",…}] (frontend features/dashboard/types.ts).';
comment on column user_dashboard.version is 'Optimistic lock: PUT must send the version it read.';

create unique index uq_user_dashboard_name on user_dashboard (user_id, lower(name));
create unique index uq_user_dashboard_default on user_dashboard (user_id) where is_default;
