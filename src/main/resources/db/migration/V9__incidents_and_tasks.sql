-- RF-INC-01..05: alerts group into an incident episode; identity avoids one incident per reading.

create type incident_status as enum ('NEW', 'ASSIGNED', 'IN_REVIEW', 'MONITORING', 'RESOLVED', 'DISCARDED');
create type incident_resolution as enum ('RESOLVED', 'DISCARDED');

create table incident (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    rule_id         uuid references rule (id),
    content_unit_id uuid references content_unit (id),
    deposit_id      uuid references deposit (id),
    priority        alert_priority not null,
    status          incident_status not null default 'NEW',
    title           varchar(300) not null,
    opened_at       timestamptz not null default now(),
    responsible_id  uuid references app_user (id),
    silenced_until  timestamptz,
    resolution      incident_resolution,
    resolution_reason varchar(2000),
    resolved_at     timestamptz,
    resolved_by_id  uuid references app_user (id),
    created_at      timestamptz not null default now()
);

comment on table incident is 'RF-INC-01/04: silencing pauses notifications only; it never hides the row nor stops evaluation.';
comment on column incident.resolution_reason is 'RF-INC-02: resolving or discarding always requires a motive.';

create index ix_incident_status on incident (status);
create index ix_incident_content_unit on incident (content_unit_id);

create table incident_evidence (
    id              uuid primary key default gen_random_uuid(),
    incident_id     uuid not null references incident (id),
    result_id       uuid references result (id),
    sample_id       uuid references sample (id),
    note            varchar(1000) not null,
    recorded_at     timestamptz not null default now(),
    superseded      boolean not null default false
);

comment on table incident_evidence is 'RF-INC-03: a corrected evidence is marked superseded and the conclusion updates without erasing the previous version.';

create index ix_incident_evidence_incident on incident_evidence (incident_id);

create table incident_event (
    id              uuid primary key default gen_random_uuid(),
    incident_id     uuid not null references incident (id),
    event_type      varchar(40) not null,
    note            varchar(1000),
    created_by_id   uuid references app_user (id),
    created_at      timestamptz not null default now()
);

comment on table incident_event is 'RF-INC-04: acknowledgement, silencing, reassignment and severity changes are logged as distinct events.';

create index ix_incident_event_incident on incident_event (incident_id);

-- RF-TAR-01..04: tasks, manual or derived from plans and alerts.

create type task_priority as enum ('NONE', 'LOW', 'MEDIUM', 'HIGH');
create type task_status as enum ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED');

create table task (
    id                  uuid primary key default gen_random_uuid(),
    code                varchar(40) not null unique,
    title               varchar(300) not null,
    deposit_id          uuid references deposit (id),
    content_unit_id     uuid references content_unit (id),
    responsible_id      uuid references app_user (id),
    due_at              timestamptz,
    priority            task_priority not null default 'MEDIUM',
    status              task_status not null default 'PENDING',
    source_incident_id  uuid references incident (id),
    source_plan_version_id uuid references plan_version (id),
    completion_criterion varchar(1000),
    cancelled_reason    varchar(1000),
    created_at          timestamptz not null default now()
);

comment on table task is 'RF-TAR-02: executing a task must first confirm the unit is still in the expected deposit.';

create index ix_task_status on task (status);
create index ix_task_responsible on task (responsible_id);

create table task_execution (
    id              uuid primary key default gen_random_uuid(),
    task_id         uuid not null references task (id),
    executed_at     timestamptz not null default now(),
    result          varchar(120),
    observations    varchar(2000),
    sample_id       uuid references sample (id),
    recorded_by_id  uuid not null references app_user (id)
);

comment on table task_execution is 'RF-TAR-03: an analytical task may complete through its linked sample or analysis.';

create index ix_task_execution_task on task_execution (task_id);
