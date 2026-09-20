-- The analyser always exports the same sheet, so the column matching is worth keeping: the next import
-- recognises the headers and maps them on its own. Shared per centre, not per user.
create table analysis_import_template (
    id          uuid primary key default gen_random_uuid(),
    center_id   uuid not null references center (id) on delete cascade,
    name        varchar(120) not null,
    columns     jsonb not null,
    author_id   uuid not null references app_user (id),
    updated_at  timestamptz not null default now(),
    constraint uq_import_template_name unique (center_id, name)
);

comment on column analysis_import_template.columns is
    '[{"header":"Aci. Total TH2","target":"param:TOTAL_ACIDITY_TH2"}] in the pasted column order.';
