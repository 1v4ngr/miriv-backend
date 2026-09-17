-- RF-DEP-01..04: the physical vessel is modelled independently from the product it holds.

create type deposit_status as enum ('AVAILABLE', 'OCCUPIED', 'PENDING_CLEANING', 'CLEANING', 'MAINTENANCE');

create table deposit (
    id                      uuid primary key default gen_random_uuid(),
    code                    varchar(30) not null,
    center_id               uuid not null references center (id),
    zone_id                 uuid references zone (id),
    name                    varchar(120),
    nominal_capacity_liters numeric(12, 2),
    useful_capacity_liters  numeric(12, 2) not null check (useful_capacity_liters > 0),
    material                varchar(60),
    refrigerated            boolean not null default false,
    status                  deposit_status not null default 'AVAILABLE',
    observations            varchar(1000),
    active                  boolean not null default true,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    constraint uq_deposit_center_code unique (center_id, code)
);

comment on table deposit is 'RF-DEP-01: renaming a deposit code never changes its historical identity (surrogate id).';
comment on column deposit.useful_capacity_liters is 'RF-DEP-04: entries above this capacity must be rejected before confirmation (CA-DEP-01).';

create index ix_deposit_center_zone on deposit (center_id, zone_id);
create index ix_deposit_status on deposit (status);

create table deposit_capacity_adjustment (
    id              uuid primary key default gen_random_uuid(),
    deposit_id      uuid not null references deposit (id),
    previous_liters numeric(12, 2) not null,
    new_liters      numeric(12, 2) not null,
    reason          varchar(1000) not null,
    author_id       uuid not null references app_user (id),
    created_at      timestamptz not null default now()
);

comment on table deposit_capacity_adjustment is 'RF-DEP-02: a manual volume correction is recorded as an adjustment with cause, date and author.';

create table deposit_cleaning_record (
    id              uuid primary key default gen_random_uuid(),
    deposit_id      uuid not null references deposit (id),
    action          varchar(120) not null,
    responsible_id  uuid references app_user (id),
    performed_at    timestamptz not null default now(),
    result          varchar(200),
    notes           varchar(1000)
);

create index ix_deposit_cleaning_deposit on deposit_cleaning_record (deposit_id);
