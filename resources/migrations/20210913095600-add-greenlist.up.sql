CREATE TABLE greenlist
(id bigserial not null primary key,
 tenant_id bigint not null REFERENCES tenant(id) ON DELETE CASCADE,
 valid_from date not null,
 valid_to date not null,
 email varchar(255) not null,
 created_date date not null default now(),
 created_by varchar(255) not null,
 description varchar(255)
);
