-- RF-CAT-01: catalogs are configuration, independent from oenological rules (RN-01).
-- Every catalog shares the same shape: stable code, display name, active flag.

create table product_type (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    description     varchar(500),
    active          boolean not null default true
);

create table color (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    active          boolean not null default true
);

create table destination (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    active          boolean not null default true
);

create table internal_category (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    description     varchar(500),
    active          boolean not null default true
);

create table variety (
    id              uuid primary key default gen_random_uuid(),
    code            varchar(40) not null unique,
    name            varchar(120) not null,
    active          boolean not null default true
);

comment on column internal_category.description is 'Commercial or quality classification decided by the cooperative (RF-CAT-01).';
