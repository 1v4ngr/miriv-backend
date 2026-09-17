create table user_profile (
    user_id       uuid primary key references app_user (id) on delete cascade,
    first_name    varchar(120) not null,
    last_name     varchar(160) not null default '',
    avatar_url    varchar(2048),
    job_title     varchar(120),
    updated_at    timestamptz not null default now()
);

comment on table user_profile is 'Extended user data. Email remains in app_user because it is the authentication identifier.';

insert into user_profile (user_id, first_name, last_name, job_title)
select id,
       split_part(full_name, ' ', 1),
       coalesce(nullif(trim(substr(full_name, length(split_part(full_name, ' ', 1)) + 1)), ''), ''),
       case when exists (
           select 1 from app_user_role ur join role r on r.id = ur.role_id
           where ur.user_id = app_user.id and r.code = 'ADMIN'
       ) then 'Administrator' else null end
from app_user
on conflict (user_id) do nothing;
