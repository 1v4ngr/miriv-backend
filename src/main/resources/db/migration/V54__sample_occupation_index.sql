-- The deposit list shows the last sample of each occupation (max taken_at per occupation_id).
create index if not exists ix_sample_occupation_taken on sample (occupation_id, taken_at desc);
