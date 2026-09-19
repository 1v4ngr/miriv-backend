-- F4-01: planned movements + before/after balances + audit attribution.
--
-- A "planned" movement is a transfer/mixture/exit that has been reserved against the
-- current data but not yet executed: no occupation/content_unit is touched. The body of
-- the request is duplicated column-by-column so a later POST /api/movements/{code}/execute
-- can apply it without asking the user to retype everything. The cancelled_reason /
-- registered_by_id / registered_at columns cover the lifecycle (created, executed,
-- cancelled) so any state can be reconstructed from movement_line alone.
--
-- Each block uses ALTER ... IF NOT EXISTS because a previous instance of this script may
-- have partially landed on a hand-repaired database and Flyway refuses to re-run a failed
-- migration once a row is in flyway_schema_history.

do $$
begin
  -- movement table
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='planned_source_deposit') then
    alter table movement add column planned_source_deposit varchar(40);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='planned_destination_deposit') then
    alter table movement add column planned_destination_deposit varchar(40);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='planned_volume_liters') then
    alter table movement add column planned_volume_liters numeric(12, 2);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='planned_loss_liters') then
    alter table movement add column planned_loss_liters numeric(12, 2) default 0;
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='planned_authorize_mixture') then
    alter table movement add column planned_authorize_mixture boolean default false;
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement' and column_name='registered_by_id') then
    alter table movement add column registered_by_id uuid references app_user (id);
  end if;
  -- cancelled_reason / registered_at / cancelled_at / created_at may already exist on
  -- databases reconciled manually; the do-blocks below leave them untouched when present.

  -- movement_line table
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement_line' and column_name='source_before_liters') then
    alter table movement_line add column source_before_liters numeric(12, 2);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement_line' and column_name='source_after_liters') then
    alter table movement_line add column source_after_liters numeric(12, 2);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement_line' and column_name='destination_before_liters') then
    alter table movement_line add column destination_before_liters numeric(12, 2);
  end if;
  if not exists (select 1 from information_schema.columns where table_schema='public' and table_name='movement_line' and column_name='destination_after_liters') then
    alter table movement_line add column destination_after_liters numeric(12, 2);
  end if;
end
$$;

create index if not exists ix_movement_effective_at on movement (effective_at desc);
create index if not exists ix_movement_status_effective on movement (status, effective_at desc);