-- V55 went too far: the work recorded under ivan.gomez was meant to pass to mireia.gomez (that stays), but the
-- account itself must keep working. This restores it: username, e-mail, name, the original password hash,
-- active again, its centre, a profile and its role. Its role was not kept by V55; the account created centres,
-- purged one and created users, which only a super administrator can do, so SUPER_ADMIN is given back.
--
-- Guarded: it only acts on the exact account V55 anonymised, so it is a no-op in any other environment.
do $$
declare
    ivan   constant uuid := 'f21288cf-bfed-4e28-9d1d-772916426a85';
    home_center constant uuid := 'c210ea9e-db2f-49a2-a332-39cdd989f056';
begin
    if not exists (select 1 from app_user where id = ivan and username = 'usuario-retirado-f21288cf') then
        raise notice 'V56: anonymised account not found, nothing to do';
        return;
    end if;

    update app_user
       set username       = 'ivan.gomez',
           email          = 'ivan.gomez@miriv.com',
           full_name      = 'Ivan Gomez',
           password_hash  = '$2b$10$Ke.PZKEuWCtmt2/JTPcS6u5JbBZzKOyJd6C6gRYOeGNXTTMbYXDEq',
           center_id      = home_center,
           active         = true,
           deactivated_at = null
     where id = ivan;

    insert into app_user_center (user_id, center_id)
    select ivan, home_center where exists (select 1 from center c where c.id = home_center)
    on conflict do nothing;

    insert into user_profile (user_id, first_name, last_name, updated_at)
    values (ivan, 'Ivan', 'Gomez', now())
    on conflict (user_id) do nothing;

    insert into app_user_role (user_id, role_id, zone_id)
    select ivan, r.id, null from role r
     where r.code = 'SUPER_ADMIN'
       and not exists (select 1 from app_user_role ur where ur.user_id = ivan and ur.role_id = r.id);
end $$;
