-- The work recorded under the account ivan.gomez passes to mireia.gomez: responsibility and authorship of lots,
-- movements, samples, results, validations and templates. Ivan's own account data (profile, roles, centres,
-- dashboards, permission grants) is removed and the account is anonymised and disabled: no name, username or
-- e-mail of it remains visible in the application.
--
-- Deliberately untouched: audit_log and login_attempt. They are the security record of what happened and are
-- not rewritten; their rows keep pointing to the (now anonymous, disabled) account.
--
-- Guarded: it only acts when both exact accounts exist, so it is a no-op in any other environment.
do $$
declare
    ivan   constant uuid := 'f21288cf-bfed-4e28-9d1d-772916426a85';
    mireia constant uuid := '365ad71d-7baf-4338-998f-5cc04c7c29a5';
begin
    if not exists (select 1 from app_user where id = ivan and username = 'ivan.gomez')
       or not exists (select 1 from app_user where id = mireia and username = 'mireia.gomez') then
        raise notice 'V55: ivan.gomez / mireia.gomez not found, nothing to do';
        return;
    end if;

    -- Responsibility and authorship of the work.
    update lot                         set responsible_id   = mireia where responsible_id   = ivan;
    update movement                    set responsible_id   = mireia where responsible_id   = ivan;
    update movement                    set registered_by_id = mireia where registered_by_id = ivan;
    update sample                      set taken_by_id      = mireia where taken_by_id      = ivan;
    update result                      set created_by_id    = mireia where created_by_id    = ivan;
    update result                      set validated_by_id  = mireia where validated_by_id  = ivan;
    update analysis                    set validated_by_id  = mireia where validated_by_id  = ivan;
    update analysis_import_template    set author_id        = mireia where author_id        = ivan;
    update alert_ack                   set acknowledged_by_id = mireia where acknowledged_by_id = ivan;
    update alert_rule                  set created_by       = mireia where created_by       = ivan;
    update blend_simulation            set user_id          = mireia where user_id          = ivan;
    update deposit_cleaning_record     set responsible_id   = mireia where responsible_id   = ivan;
    update elaboration_plan            set responsible_id   = mireia where responsible_id   = ivan;
    update fermentation_state          set confirmed_by_id  = mireia where confirmed_by_id  = ivan;
    update fermentation_state_review   set reviewed_by_id   = mireia where reviewed_by_id   = ivan;
    update plan_version                set author_id        = mireia where author_id        = ivan;
    update report_job                  set author_id        = mireia where author_id        = ivan;
    update content_phase               set changed_by_id    = mireia where changed_by_id    = ivan;
    update app_user_permission_grant   set granted_by_id    = mireia where granted_by_id    = ivan;

    -- Ivan's own account data: Mireia keeps hers.
    delete from app_user_permission_grant where user_id = ivan;
    delete from app_user_role             where user_id = ivan;
    delete from app_user_center           where user_id = ivan;
    delete from user_dashboard            where user_id = ivan;
    delete from user_profile              where user_id = ivan;

    -- The account itself: anonymous, disabled and impossible to sign in with.
    update app_user
       set username       = 'usuario-retirado-f21288cf',
           email          = 'retirado-f21288cf@invalid.miriv',
           full_name      = 'Usuario retirado',
           password_hash  = '!disabled',
           active         = false,
           deactivated_at = coalesce(deactivated_at, now())
     where id = ivan;
end $$;
