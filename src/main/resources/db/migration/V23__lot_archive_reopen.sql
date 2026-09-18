alter table lot add column reopened_at timestamptz;
alter table lot add column reopened_reason varchar(1000);