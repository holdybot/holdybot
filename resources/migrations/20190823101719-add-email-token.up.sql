CREATE TABLE email_token
(id bigserial not null primary key,
 created timestamp default now() not null,
 email varchar(255) not null,
 host varchar(255) not null,
 token varchar(255) not null,
 CONSTRAINT email_host_uk UNIQUE (host, email));