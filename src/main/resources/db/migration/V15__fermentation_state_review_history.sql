create table fermentation_state_review (
    id uuid primary key default gen_random_uuid(),
    content_unit_id uuid not null references content_unit (id),
    process fermentation_process not null,
    previous_status varchar(60),
    decision varchar(60) not null,
    reason varchar(1000) not null,
    reviewed_by_id uuid not null references app_user (id),
    reviewed_at timestamptz not null default now()
);

create index ix_fermentation_state_review_content
    on fermentation_state_review (content_unit_id, process, reviewed_at desc);
