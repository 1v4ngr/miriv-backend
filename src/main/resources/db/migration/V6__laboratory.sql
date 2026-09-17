-- Section 6/7: analytical parameters, panels, samples, analyses and results.

create table parameter (
    id                  uuid primary key default gen_random_uuid(),
    code                varchar(60) not null unique,
    name                varchar(120) not null,
    reference_unit      varchar(40) not null,
    decimal_places      smallint not null default 2,
    plausibility_min    numeric(14, 4),
    plausibility_max    numeric(14, 4),
    description         varchar(1000)
);

comment on table parameter is 'RF-PAR-01: instrumental plausibility limits are distinct from a lot''s oenological targets.';

create table parameter_method (
    id                      uuid primary key default gen_random_uuid(),
    parameter_id            uuid not null references parameter (id),
    code                    varchar(60) not null,
    name                    varchar(120) not null,
    quantification_limit    numeric(14, 4),
    detection_limit         numeric(14, 4),
    constraint uq_parameter_method unique (parameter_id, code)
);

comment on table parameter_method is 'RN-07: detection and quantification limits belong to the method and its version.';

create table analysis_panel (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(60) not null unique,
    name            varchar(120) not null,
    description     varchar(500)
);

create table analysis_panel_parameter (
    id              uuid primary key default gen_random_uuid(),
    panel_id        uuid not null references analysis_panel (id),
    parameter_id    uuid not null references parameter (id),
    required        boolean not null default true,
    constraint uq_panel_parameter unique (panel_id, parameter_id)
);

-- RF-ANA-01: the content is resolved by sampling date, not by the deposit's current content.
create table sample (
    id                      uuid primary key default gen_random_uuid(),
    code                    varchar(40) not null unique,
    content_unit_id         uuid not null references content_unit (id),
    occupation_id           uuid not null references occupation (id),
    deposit_id_at_sampling  uuid not null references deposit (id),
    sampling_point          varchar(120),
    taken_at                timestamptz not null,
    received_at             timestamptz,
    taken_by_id             uuid not null references app_user (id),
    observations            varchar(1000),
    created_at              timestamptz not null default now()
);

comment on table sample is 'RF-ANA-01/RF-ANA-02: taken_at is the fact time; received/processed/validated are recorded separately.';

create index ix_sample_content_unit on sample (content_unit_id);
create index ix_sample_taken_at on sample (taken_at);

create type analysis_status as enum ('DRAFT', 'IN_PROGRESS', 'PARTIAL', 'PENDING_VALIDATION', 'VALIDATED', 'INVALIDATED');

create table analysis (
    id              uuid primary key default gen_random_uuid(),
    sample_id       uuid not null references sample (id),
    panel_id        uuid references analysis_panel (id),
    laboratory_name varchar(200),
    status          analysis_status not null default 'DRAFT',
    requested_at    timestamptz not null default now(),
    processed_at    timestamptz,
    validated_at    timestamptz,
    validated_by_id uuid references app_user (id)
);

comment on table analysis is 'RF-ANA-05: an analysis is complete only when every mandatory parameter of its panel is resolved.';

create index ix_analysis_sample on analysis (sample_id);
create index ix_analysis_status on analysis (status);

create type result_qualifier as enum ('NONE', 'LESS_THAN', 'NOT_MEASURED', 'NOT_DETECTED');

create table result (
    id                  uuid primary key default gen_random_uuid(),
    analysis_id         uuid not null references analysis (id),
    parameter_id        uuid not null references parameter (id),
    method_id           uuid references parameter_method (id),
    qualifier           result_qualifier not null default 'NONE',
    numeric_value        numeric(14, 4),
    qualifier_limit     numeric(14, 4),
    original_value      varchar(60),
    original_unit       varchar(40),
    equipment           varchar(120),
    is_current          boolean not null default true,
    supersedes_result_id uuid references result (id),
    validated           boolean not null default false,
    validated_by_id     uuid references app_user (id),
    validated_at        timestamptz,
    created_by_id       uuid not null references app_user (id),
    created_at          timestamptz not null default now()
);

comment on table result is 'RF-ANA-06/RF-ANA-08: corrections are new versions; is_current marks the value used by trends and rules.';
comment on column result.qualifier_limit is 'CA-ANA-02: a "less than" qualifier keeps its limit; it is never turned into 0 or into an exact reading.';

create index ix_result_analysis on result (analysis_id);
create index ix_result_parameter_current on result (parameter_id, is_current);

-- Only one current result per analysis/parameter (the vigent value referred to by RF-ANA-08).
create unique index uq_result_current_per_parameter on result (analysis_id, parameter_id) where is_current;
