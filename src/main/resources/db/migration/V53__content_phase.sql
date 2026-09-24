-- Manual phase of a content. The phase is normally derived from the category and the fermentation states
-- (report_phase, V51); an enologist can set it by hand from the deposit detail (advance / go back). The
-- latest row per content wins; a row with phase_id null returns the content to the automatic phase.
-- Rows are never updated, so the table is also the history of phase changes.
create table if not exists content_phase (
    id                 uuid primary key default gen_random_uuid(),
    content_unit_id    uuid not null references content_unit (id),
    phase_id           uuid references report_phase (id) on delete set null,
    previous_phase_id  uuid references report_phase (id) on delete set null,
    reason             varchar(500),
    changed_by_id      uuid references app_user (id),
    changed_at         timestamptz not null default now()
);

create index if not exists ix_content_phase_content_changed on content_phase (content_unit_id, changed_at desc);
