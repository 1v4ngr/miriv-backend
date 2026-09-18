create table login_attempt (
    username varchar(120) not null,
    attempted_at timestamptz not null default now(),
    successful boolean not null
);
create index ix_login_attempt_username_time on login_attempt (username, attempted_at desc);