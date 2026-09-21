-- Reports (UI23). Two pieces:
--
-- 1) report_phase: the phases the cellar report groups deposits by, and the parameters charted in each.
--    A content's phase is not stored: it is derived from its category and its alcoholic / malolactic
--    fermentation states, taking the first active phase (by position) whose criteria match. Because the
--    state reviews and the must → wine reclassification are dated, the same rule also rebuilds the phase a
--    content was in on any past date (a must while it ferments, a wine once the alcoholic fermentation ends).
--    Empty criteria lists mean "any"; the pseudo-state NONE matches a content with no state recorded.
--
-- 2) report_job: every issued report, immutable once AVAILABLE (RF-INF-01). Correcting a result later does
--    not change an issued report: a new one is generated. The PDF and the raw .xlsx are stored side by side.

create table report_phase (
    id                 uuid primary key default gen_random_uuid(),
    code               varchar(40) not null unique,
    name               varchar(120) not null,
    description        varchar(500),
    color              varchar(9) not null default '#6d4656',
    position           integer not null default 0,
    active             boolean not null default true,
    category_codes     jsonb not null default '[]'::jsonb,
    alcoholic_states   jsonb not null default '[]'::jsonb,
    malolactic_states  jsonb not null default '[]'::jsonb,
    parameter_codes    jsonb not null default '[]'::jsonb,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

comment on table report_phase is
    'Phases of the cellar report: first active row (by position) whose category / alcoholic / malolactic criteria match wins.';
comment on column report_phase.parameter_codes is 'Ordered parameter codes charted and tabulated while a content is in this phase.';

insert into report_phase (code, name, description, color, position, category_codes, alcoholic_states, malolactic_states, parameter_codes) values
  ('MUST_PENDING', 'Mosto sin iniciar', 'Mosto a la espera de que arranque la fermentación alcohólica.', '#8a8f2a', 1,
   '["MUST"]', '["NOT_STARTED"]', '[]',
   '["DENSITY", "CONTENT_TEMPERATURE", "REDUCING_SUGARS", "POTENTIAL_ALCOHOL", "PH", "TOTAL_ACIDITY", "YAN"]'),
  ('ALCOHOLIC', 'Fermentación alcohólica', 'Mosto en fermentación: densidad y temperatura mandan.', '#c0782a', 2,
   '["MUST"]', '["ACTIVE", "SLOW", "SUSPECTED_STOP", "NONE"]', '[]',
   '["DENSITY", "CONTENT_TEMPERATURE", "REDUCING_SUGARS", "VOLATILE_ACIDITY", "PH", "TOTAL_ACIDITY", "YAN"]'),
  ('MALOLACTIC', 'Fermentación maloláctica', 'Fermentación alcohólica terminada y maloláctica en curso o pendiente.', '#2e7d9a', 3,
   '[]', '["FINISHED"]', '["NOT_STARTED", "ACTIVE", "SLOW"]',
   '["L_MALIC_ACID", "MALIC_ACID", "L_LACTIC_ACID", "VOLATILE_ACIDITY", "PH", "FREE_SO2", "CONTENT_TEMPERATURE"]'),
  ('WINE', 'Vino / conservación', 'Vino hecho: conservación y estabilidad.', '#6d4656', 4,
   '[]', '[]', '[]',
   '["FREE_SO2", "TOTAL_SO2", "VOLATILE_ACIDITY", "PH", "TOTAL_ACIDITY", "ETHANOL", "REDUCING_SUGARS", "L_MALIC_ACID", "COLOR_INTENSITY"]');

create table report_job (
    id                   uuid primary key default gen_random_uuid(),
    code                 varchar(40) not null unique,
    center_id            uuid not null references center (id),
    type                 varchar(30) not null,
    title                varchar(200) not null,
    filters              jsonb not null,
    include_provisional  boolean not null default false,
    status               varchar(20) not null,
    error                varchar(1000),
    record_count         integer,
    deposit_count        integer,
    pdf_name             varchar(200),
    pdf_content          bytea,
    xlsx_name            varchar(200),
    xlsx_content         bytea,
    author_id            uuid not null references app_user (id),
    created_at           timestamptz not null default now(),
    finished_at          timestamptz,
    constraint ck_report_job_status check (status in ('PREPARING', 'AVAILABLE', 'FAILED'))
);

comment on table report_job is 'RF-INF-01: issued reports. The stored files never change; corrected data means a new report.';

create index ix_report_job_center_created on report_job (center_id, created_at desc);
