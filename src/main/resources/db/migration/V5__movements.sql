-- RF-MOV-01..06: entries, transfers, mixes, splits, exits, losses and adjustments.

create type movement_type as enum ('ENTRY', 'TRANSFER_FULL', 'TRANSFER_PARTIAL', 'MIX', 'SPLIT', 'EXIT', 'LOSS', 'ADJUSTMENT');
create type movement_status as enum ('PLANNED', 'EXECUTED', 'CANCELLED');

create table movement (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    type            movement_type not null,
    status          movement_status not null default 'PLANNED',
    effective_at    timestamptz not null,
    registered_at   timestamptz not null default now(),
    responsible_id  uuid not null references app_user (id),
    reason          varchar(1000) not null,
    idempotency_key varchar(80) unique,
    cancelled_reason varchar(1000),
    cancelled_at    timestamptz,
    created_at      timestamptz not null default now()
);

comment on column movement.idempotency_key is 'RF-MOV-03: a repeated confirmation of the same operation must not duplicate it.';
comment on table movement is 'RF-MOV-03: all lines of a movement are confirmed atomically; a failed validation updates nothing.';

create index ix_movement_effective_at on movement (effective_at);

-- Each line moves a volume from an optional source (deposit/content unit) to an optional destination.
-- Entries have no source line; exits and losses have no destination line.
create table movement_line (
    id                          uuid primary key default gen_random_uuid(),
    movement_id                 uuid not null references movement (id),
    source_content_unit_id      uuid references content_unit (id),
    source_deposit_id           uuid references deposit (id),
    destination_content_unit_id uuid references content_unit (id),
    destination_deposit_id      uuid references deposit (id),
    volume_liters               numeric(12, 2) not null check (volume_liters > 0),
    loss_liters                 numeric(12, 2) not null default 0 check (loss_liters >= 0)
);

comment on table movement_line is 'RF-MOV-02: differences are always identified as loss, measurement variation or documented adjustment.';

create index ix_movement_line_movement on movement_line (movement_id);
create index ix_movement_line_source_content_unit on movement_line (source_content_unit_id);
create index ix_movement_line_destination_content_unit on movement_line (destination_content_unit_id);

-- Genealogy: which content units a unit descends from, and through which movement (section 3).
create table content_unit_lineage (
    id                      uuid primary key default gen_random_uuid(),
    content_unit_id         uuid not null references content_unit (id),
    parent_content_unit_id  uuid not null references content_unit (id),
    movement_id             uuid not null references movement (id),
    contributed_liters      numeric(12, 2),
    note                    varchar(500),
    constraint uq_content_unit_lineage unique (content_unit_id, parent_content_unit_id, movement_id)
);

comment on table content_unit_lineage is 'RF-MOV-04/section 3: a mix keeps origin proportions; a split keeps parentage without inventing measurements for the children.';

create index ix_lineage_content_unit on content_unit_lineage (content_unit_id);
create index ix_lineage_parent on content_unit_lineage (parent_content_unit_id);
