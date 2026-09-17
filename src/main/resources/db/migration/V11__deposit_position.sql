-- A physical position is distinct from the named zone and remains stable in deposit views.
alter table deposit add column position varchar(80);
