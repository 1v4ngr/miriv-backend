-- RF-LOT-01..03: a lot is a traceable grouping of product; it may split into several content units.
-- Section 3: "a lot may have several content units; each has its own tracking."

create table lot (
    id                  uuid primary key default gen_random_uuid(),
    code                varchar(40) not null unique,
    campaign            integer not null,
    category_id         uuid references internal_category (id),
    color_id            uuid references color (id),
    destination_id      uuid references destination (id),
    entry_date          date not null,
    responsible_id      uuid not null references app_user (id),
    origin_summary      varchar(500),
    archived            boolean not null default false,
    archived_reason     varchar(1000),
    created_at          timestamptz not null default now()
);

comment on column lot.origin_summary is 'RF-LOT-01: origin and varieties may remain pending; shown as incomplete information, never fabricated.';

create index ix_lot_campaign on lot (campaign);

create table lot_origin_line (
    id              uuid primary key default gen_random_uuid(),
    lot_id          uuid not null references lot (id),
    source_description varchar(200) not null,
    reference       varchar(120),
    percentage      numeric(5, 2) check (percentage is null or (percentage >= 0 and percentage <= 100))
);

comment on table lot_origin_line is 'RF-LOT-02: known percentages are validated; unknown percentages are left empty, never guessed.';

create table lot_variety (
    id              uuid primary key default gen_random_uuid(),
    lot_id          uuid not null references lot (id),
    variety_id      uuid not null references variety (id),
    percentage      numeric(5, 2) check (percentage is null or (percentage >= 0 and percentage <= 100)),
    constraint uq_lot_variety unique (lot_id, variety_id)
);

-- A content unit is an identifiable fraction of a lot with its own volume and follow-up (section 3).
create table content_unit (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    lot_id          uuid not null references lot (id),
    category_id     uuid references internal_category (id),
    color_id        uuid references color (id),
    volume_liters   numeric(12, 2) not null check (volume_liters >= 0),
    active          boolean not null default true,
    created_at      timestamptz not null default now()
);

create index ix_content_unit_lot on content_unit (lot_id);

-- An occupation relates a content unit to a deposit for a time interval (section 3).
create table occupation (
    id              uuid primary key default gen_random_uuid(),
    content_unit_id uuid not null references content_unit (id),
    deposit_id      uuid not null references deposit (id),
    start_at        timestamptz not null,
    end_at          timestamptz,
    volume_liters   numeric(12, 2) not null check (volume_liters >= 0)
);

comment on table occupation is 'RN section 3: a deposit has many historical occupations but at most one active homogeneous occupation.';

create index ix_occupation_content_unit on occupation (content_unit_id);
create index ix_occupation_deposit on occupation (deposit_id);

-- Only one active (end_at is null) occupation per deposit, and per content unit.
create unique index uq_occupation_active_deposit on occupation (deposit_id) where end_at is null;
create unique index uq_occupation_active_content_unit on occupation (content_unit_id) where end_at is null;
