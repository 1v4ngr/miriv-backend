-- F7-04: saved blend simulations, shared by the enologists of a center.
create table blend_simulation (
    id                        uuid primary key default gen_random_uuid(),
    center_id                 uuid not null references center (id),
    user_id                   uuid references app_user (id) on delete set null,
    name                      varchar(120) not null,
    status                    varchar(20) not null default 'DRAFT' check (status in ('DRAFT', 'CONVERTED')),
    destination_deposit_code  varchar(40),
    payload                   jsonb not null,
    result                    jsonb,
    task_id                   uuid references task (id) on delete set null,
    planned_movements         jsonb not null default '[]'::jsonb,
    version                   integer not null default 0,
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now()
);

comment on column blend_simulation.payload is '{"components":[{"contentCode","depositCode","volumeLiters"}],"additions":[{"type","amount","waterTemperature"}],"targets":[…],"goal":{…},"totalMin","totalMax"}';
comment on column blend_simulation.planned_movements is 'Codes of the PLANNED movements created when the simulation was converted into a task.';
comment on column blend_simulation.result is 'Snapshot of the simulated parameters and checks when saved (display only; never trusted by the server).';

create unique index uq_blend_simulation_name on blend_simulation (center_id, lower(name));
