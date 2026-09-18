alter table lot add column archived_at timestamptz;

comment on column lot.archived_at is 'Timestamp when the lot was archived (soft delete).';
