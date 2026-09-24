-- Deposit locations are zones. Besides Exterior (E) and Interior (I), every center gets Suelo (S):
-- underground / floor-level tanks. Centers that already have an S zone are left untouched.
insert into zone (center_id, code, name)
select c.id, 'S', 'Suelo'
  from center c
 where not exists (select 1 from zone z where z.center_id = c.id and z.code = 'S');
