-- RF-OPE-01..04: cellar operations record planned vs. actual execution.

create type operation_type as enum (
    'INOCULATION', 'NUTRITION', 'SULFITING', 'CORRECTION', 'PUMP_OVER',
    'AERATION', 'SETPOINT_CHANGE', 'FILTRATION', 'STABILIZATION', 'CLEANING'
);
create type operation_status as enum ('PLANNED', 'EXECUTED', 'EXECUTED_WITH_DEVIATION', 'CANCELLED');

create table operation (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    type            operation_type not null,
    content_unit_id uuid not null references content_unit (id),
    deposit_id      uuid not null references deposit (id),
    responsible_id  uuid not null references app_user (id),
    planned_at      timestamptz,
    executed_at     timestamptz,
    status          operation_status not null default 'PLANNED',
    follow_up_note  varchar(1000),
    created_at      timestamptz not null default now()
);

comment on table operation is 'RF-OPE-03: a partial execution keeps both planned and actual quantity, deviation and explanation.';

create index ix_operation_content_unit on operation (content_unit_id);
create index ix_operation_status on operation (status);

create table operation_addition (
    id                      uuid primary key default gen_random_uuid(),
    operation_id            uuid not null references operation (id),
    product_name            varchar(120) not null,
    commercial_lot          varchar(80),
    planned_quantity        numeric(14, 4),
    actual_quantity         numeric(14, 4),
    unit                    varchar(20) not null,
    concentration           varchar(60),
    treated_volume_liters   numeric(12, 2),
    deviation_reason        varchar(1000)
);

comment on table operation_addition is 'RF-OPE-02: distinguishes commercial product quantity from active-substance quantity.';

-- RF-EVA-08: a rule definition, versioned and approved before it can raise alerts.

create type alert_priority as enum ('INFORMATIVE', 'PREVENTIVE', 'HIGH', 'URGENT');

create table rule (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(20) not null unique,
    name            varchar(200) not null,
    scope           varchar(120) not null,
    default_severity alert_priority not null default 'PREVENTIVE',
    description     varchar(2000),
    active          boolean not null default true
);

comment on table rule is 'Section 11/12: AL-01..AL-14 style rule catalog (RF-EVA-08).';

create table rule_version (
    id                  uuid primary key default gen_random_uuid(),
    rule_id             uuid not null references rule (id),
    version_number      integer not null,
    condition_definition jsonb not null,
    persistence_measurements smallint,
    max_age_hours       integer,
    min_measurements    smallint,
    window_days         integer,
    approved            boolean not null default false,
    approved_by_id      uuid references app_user (id),
    approved_at         timestamptz,
    effective_at        timestamptz,
    created_at          timestamptz not null default now(),
    constraint uq_rule_version unique (rule_id, version_number)
);

comment on table rule_version is 'RF-EVA-08/RF-EVA-09: only approved versions raise operational alerts; simulation runs against drafts without notifying.';

create index ix_rule_version_rule on rule_version (rule_id);

alter table fermentation_state
    add constraint fk_fermentation_state_rule_version foreign key (rule_version_id) references rule_version (id);
