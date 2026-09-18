create table laboratory (
    id          uuid primary key default gen_random_uuid(),
    code        varchar(40) not null,
    name        varchar(200) not null,
    center_id   uuid not null references center (id) on delete cascade,
    active      boolean not null default true,
    created_at  timestamptz not null default now(),
    unique (center_id, code)
);

comment on table laboratory is 'Catalog of destination labs available for samples (one internal lab per center by default).';

insert into laboratory (code, name, center_id)
select 'INTERNAL-' || upper(replace(c.code, '-', '_')),
       'Interno · ' || c.name,
       c.id
from center c;

create index ix_laboratory_center on laboratory (center_id);
