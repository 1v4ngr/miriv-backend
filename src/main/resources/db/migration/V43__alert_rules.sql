-- Tracking alerts: configurable conditions evaluated against the latest analyses of every occupied tank
-- (e.g. "fermentation finished": density low and stable). Shown on the dashboard, acknowledgeable per tank.

create table alert_rule (
    id           uuid primary key default gen_random_uuid(),
    center_id    uuid not null references center (id),
    name         varchar(120) not null,
    severity     varchar(10) not null default 'INFO' check (severity in ('INFO', 'WARN', 'CRIT')),
    conditions   jsonb not null,
    category_id  uuid references internal_category (id),
    content_unit_id uuid references content_unit (id) on delete cascade,
    phases       jsonb not null default '[]'::jsonb,
    active       boolean not null default true,
    created_by   uuid references app_user (id) on delete set null,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

comment on column alert_rule.conditions is 'All must hold: [{"parameter":"DENSITY","type":"LTE|GTE|STABLE","value":0.998,"days":2,"tolerance":0.0015}]';
comment on column alert_rule.content_unit_id is 'When set, the rule watches only this content (values fixed for one wine).';
comment on column alert_rule.phases is 'Alcoholic-fermentation states the rule applies to (empty = any), e.g. ["ACTIVE","SLOW"].';
create unique index uq_alert_rule_name on alert_rule (center_id, lower(name));

create table alert_ack (
    rule_id            uuid not null references alert_rule (id) on delete cascade,
    content_unit_id    uuid not null references content_unit (id) on delete cascade,
    sample_code        varchar(40) not null,
    acknowledged_by_id uuid references app_user (id) on delete set null,
    acknowledged_at    timestamptz not null default now(),
    primary key (rule_id, content_unit_id)
);

comment on table alert_ack is 'An alert stays hidden while the sample that triggered it is the same one; a newer sample can raise it again.';

-- Starting rules for every center (editable, can be switched off).
insert into alert_rule (center_id, name, severity, conditions, phases)
select c.id, r.name, r.severity, r.conditions::jsonb, r.phases::jsonb
from center c
cross join (values
  ('Fermentación alcohólica terminada', 'INFO',
   '[{"parameter":"DENSITY","type":"LTE","value":0.998},{"parameter":"DENSITY","type":"STABLE","days":2,"tolerance":0.0015}]',
   '["ACTIVE","SLOW","SUSPECTED_STOP","NOT_EVALUATED"]'),
  ('Azúcares agotados', 'INFO',
   '[{"parameter":"REDUCING_SUGARS","type":"LTE","value":2}]',
   '["ACTIVE","SLOW","SUSPECTED_STOP","NOT_EVALUATED"]'),
  ('Posible parada de fermentación', 'WARN',
   '[{"parameter":"DENSITY","type":"GTE","value":1.005},{"parameter":"DENSITY","type":"STABLE","days":3,"tolerance":0.0015}]',
   '["ACTIVE","SLOW","SUSPECTED_STOP"]')
) as r(name, severity, conditions, phases);

-- Values fixed for ONE content: a target row can be scoped to a content, and then it beats every category / phase row.
alter table parameter_target add column content_unit_id uuid references content_unit (id) on delete cascade;
drop index uq_parameter_target_scope;
create unique index uq_parameter_target_scope on parameter_target (
    parameter_id,
    coalesce(category_id, '00000000-0000-0000-0000-000000000000'),
    coalesce(phase, ''),
    coalesce(content_unit_id, '00000000-0000-0000-0000-000000000000')
);
comment on column parameter_target.content_unit_id is 'When set, this range applies only to that content and wins over category / phase / global ranges.';
