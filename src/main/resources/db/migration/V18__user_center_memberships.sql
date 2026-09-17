create table app_user_center (
    user_id   uuid not null references app_user (id) on delete cascade,
    center_id uuid not null references center (id) on delete cascade,
    primary key (user_id, center_id)
);

insert into app_user_center (user_id, center_id)
select id, center_id from app_user where center_id is not null
on conflict do nothing;
