alter table lot add column center_id uuid references center (id);
update lot set center_id = app_user.center_id from app_user where app_user.id = lot.responsible_id;
alter table lot alter column center_id set not null;
create index ix_lot_center_campaign on lot (center_id, campaign);
