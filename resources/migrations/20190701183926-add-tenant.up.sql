CREATE TABLE tenant
(id bigserial not null primary key,
 host varchar(255),
 email varchar(255),
 admin varchar(255),
 bang_seconds_utc integer not null default 64800,
 computed_date date,
 jwt_valid_after bigint default 0 not null,
 activated boolean default false not null,
 activation_token varchar(255),
 settings text,
 constraint host_uk unique (host)
);
