-- F2-05: replace UUID-derived codes (e.g. MOV-2026-A1B2C3D4) with sequential,
-- human-readable codes (MOV-2026-00001) that can be dictated and ordered.
create table code_sequence (
    prefix     varchar(10) not null,
    year       integer not null,
    last_value integer not null,
    primary key (prefix, year)
);

comment on table code_sequence is
    'F2-05: per-(prefix,year) monotonic counter used by CodeGenerator to mint short, sequential codes.';
