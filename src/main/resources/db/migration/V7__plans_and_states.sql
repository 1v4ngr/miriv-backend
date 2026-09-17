-- RF-PLAN-01..05: a versioned elaboration plan assigned to a content unit.

create type malolactic_intent as enum ('PLANNED', 'NOT_DESIRED', 'PENDING_DECISION');

create table elaboration_plan (
    id                  uuid primary key default gen_random_uuid(),
    content_unit_id     uuid not null unique references content_unit (id),
    name                varchar(200) not null,
    destination_id      uuid references destination (id),
    responsible_id      uuid not null references app_user (id),
    current_version_id  uuid,
    created_at          timestamptz not null default now()
);

comment on table elaboration_plan is 'RF-PLAN-01: each content unit carries at most one plan, built from a template or defined explicitly.';

create table plan_version (
    id                          uuid primary key default gen_random_uuid(),
    plan_id                     uuid not null references elaboration_plan (id),
    version_number              integer not null,
    alcoholic_strategy          varchar(500),
    yeast                       varchar(120),
    inoculation_date            date,
    sugar_target_g_per_l        numeric(8, 2),
    malolactic_intent           malolactic_intent not null default 'PENDING_DECISION',
    malolactic_strategy         varchar(500),
    bacteria                    varchar(120),
    malolactic_expected_at      date,
    temperature_min_celsius     numeric(5, 2),
    temperature_max_celsius     numeric(5, 2),
    sampling_panel_id           uuid references analysis_panel (id),
    sampling_frequency_days     integer,
    author_id                   uuid not null references app_user (id),
    reason                      varchar(1000),
    effective_at                timestamptz not null default now(),
    created_at                  timestamptz not null default now(),
    constraint uq_plan_version unique (plan_id, version_number)
);

comment on table plan_version is 'RF-PLAN-04: a change keeps author, reason, effective date and modified fields; history is never rewritten.';

alter table elaboration_plan
    add constraint fk_plan_current_version foreign key (current_version_id) references plan_version (id);

create index ix_plan_version_plan on plan_version (plan_id);

create table plan_phase_criterion (
    id                          uuid primary key default gen_random_uuid(),
    plan_version_id             uuid not null references plan_version (id),
    phase                       varchar(60) not null,
    parameter_id                uuid references parameter (id),
    method_required             varchar(120),
    persistence_measurements    smallint,
    tolerance                   numeric(14, 4),
    requires_human_validation   boolean not null default true
);

comment on table plan_phase_criterion is 'RF-PLAN-03: start/end criteria declare method, persistence, tolerance and whether a human must confirm.';

create index ix_plan_phase_criterion_version on plan_phase_criterion (plan_version_id);

create table plan_exception (
    id              uuid primary key default gen_random_uuid(),
    plan_version_id uuid not null references plan_version (id),
    content_unit_id uuid not null references content_unit (id),
    description     varchar(1000) not null,
    responsible_id  uuid not null references app_user (id),
    valid_from      date not null,
    valid_until     date,
    created_at      timestamptz not null default now()
);

comment on table plan_exception is 'RF-PLAN-05: an exception is scoped, time-bound and never disables unrelated rules.';

-- Section 9: the two fermentation processes are tracked independently.
create type fermentation_process as enum ('ALCOHOLIC', 'MALOLACTIC');

create table fermentation_state (
    id                  uuid primary key default gen_random_uuid(),
    content_unit_id     uuid not null references content_unit (id),
    process             fermentation_process not null,
    estimated_status    varchar(60) not null,
    estimated_at        timestamptz not null default now(),
    evidence_note       varchar(1000),
    rule_version_id     uuid,
    confirmed_status    varchar(60),
    confirmed_by_id     uuid references app_user (id),
    confirmed_at        timestamptz,
    confirmation_reason varchar(1000),
    constraint uq_fermentation_state_process unique (content_unit_id, process)
);

comment on table fermentation_state is 'RF-EST-01/06: alcoholic and malolactic states are kept independent, each with its own estimate and confirmation.';

create index ix_fermentation_state_content_unit on fermentation_state (content_unit_id);
