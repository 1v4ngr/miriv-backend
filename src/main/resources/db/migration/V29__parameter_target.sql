-- Tracking: editable warning / critical ranges per analytical parameter, optionally narrowed by
-- product category and fermentation phase. They colour the tracking matrix and draw the bands on
-- the curves. They are NOT the instrumental plausibility limits stored on "parameter".

create table parameter_target (
    id           uuid primary key default gen_random_uuid(),
    parameter_id uuid not null references parameter (id),
    category_id  uuid references internal_category (id),
    phase        varchar(40),
    warn_min     numeric(14, 4),
    warn_max     numeric(14, 4),
    crit_min     numeric(14, 4),
    crit_max     numeric(14, 4),
    note         varchar(500),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint ck_parameter_target_has_limit check (
        warn_min is not null or warn_max is not null or crit_min is not null or crit_max is not null),
    constraint ck_parameter_target_order check (
        (crit_min is null or warn_min is null or crit_min <= warn_min)
        and (warn_min is null or warn_max is null or warn_min <= warn_max)
        and (warn_max is null or crit_max is null or warn_max <= crit_max))
);

comment on table parameter_target is 'Enologist-editable ranges: values outside warn_* are a warning, outside crit_* are critical. Most specific row wins: category+phase, category, phase, global.';
comment on column parameter_target.phase is 'Optional alcoholic-fermentation state (confirmed, else estimated) this row applies to.';

create unique index uq_parameter_target_scope on parameter_target (
    parameter_id,
    coalesce(category_id, '00000000-0000-0000-0000-000000000000'),
    coalesce(phase, '')
);

-- Conservative starting values, editable from Administración.
insert into parameter_target (parameter_id, warn_max, crit_max, note)
select id, 0.6, 0.8, 'Valor inicial (g/L como acético). Ajústalo a tu criterio.'
  from parameter where code = 'VOLATILE_ACIDITY';

insert into parameter_target (parameter_id, warn_min, crit_min, note)
select id, 15, 8, 'Valor inicial (mg/L). Depende del pH y de la fase: ajústalo.'
  from parameter where code = 'FREE_SO2';

insert into parameter_target (parameter_id, warn_max, crit_max, note)
select id, 3.8, 4.0, 'Valor inicial. Un pH alto aumenta el riesgo microbiológico.'
  from parameter where code = 'PH';
