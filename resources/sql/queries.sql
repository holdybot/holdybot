-- :name add-parking! :! :n
-- :doc creates a new parking request record
INSERT INTO parking AS S
(tenant_id, email, parking_day, parking_zone, parking_name, user_name, status, on_behalf_of, parking_type)
VALUES (:tenant_id, :email, :parking_day, :parking_zone, :parking_name, :user_name, 'pending', :on_behalf_of, :parking_type)
ON CONFLICT (tenant_id, parking_zone, parking_name, parking_day, email)
    DO UPDATE SET status=excluded.status, email=excluded.email, user_name=excluded.user_name

-- :name add-visitor-parking! :! :n
-- :doc creates a new parking request record
INSERT INTO parking AS S
(tenant_id, email, parking_day, parking_zone, parking_name, user_name, status, on_behalf_of, parking_type)
VALUES (:tenant_id, :email, :parking_day, :parking_zone, :parking_name, :user_name, 'inactive', :on_behalf_of, :parking_type)
ON CONFLICT (tenant_id, parking_zone, parking_name, parking_day, email)
    DO UPDATE SET status=excluded.status, email=excluded.email, user_name=excluded.user_name

-- :name load-parking :? :1
-- :doc loads a single parking matching keys
SELECT * FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and email = :email

-- :name activate-parking! :! :n
-- :doc activates existing pending parking record
UPDATE parking
SET status = 'active', points = :points, slot_name = :slot_name, on_behalf_of = :on_behalf_of
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status in (:v*:statuses) and email = :email

-- :name deactivate-parking! :! :1
-- :doc deactivates existing pending parking record
UPDATE parking
SET status = 'blocked', points = coalesce(points,5)-1, slot_name = null, on_behalf_of = :on_behalf_of
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'active' and email = :email

-- :name deactivate-remainings! :! :n
-- :doc deactivates existing pending parking records
UPDATE parking
SET status = 'inactive'
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'pending'

-- :name get-parkings-by-day :? :*
-- :doc retrieves all parking records for given name and day
SELECT id,email,status,slot_name,user_name,on_behalf_of,parking_type,description FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day = :parking_day
ORDER BY status, id

-- :name get-visitor-parkings-by-day :? :*
-- :doc retrieves all parking records for given name and day
SELECT id,email,status,slot_name,user_name,on_behalf_of,parking_type FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day = :parking_day and email = :email and status <> 'info'
ORDER BY status, id

-- :name get-pending-parkings-by-day :? :*
-- :doc retrieves all pending parking records for given name and day
SELECT * FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day = :parking_day and status = 'pending'

-- :name get-inactive-parkings-by-day :? :*
-- :doc retrieves all inactive parking records for given name and day
SELECT * FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day = :parking_day and status = 'inactive'

-- :name get-points :? :*
-- :doc retrieves realized points of given users on given parking zone for given time
SELECT email, sum(points) as points FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_day >= :from and parking_day < :to and email in (:v*:emails) and status <> 'info'
GROUP BY email

-- :name get-parkings :? :*
-- :doc retrieves a parking records given the day
SELECT id,email,status,slot_name,user_name,parking_day,on_behalf_of,parking_type,description FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day in (:v*:dates)
ORDER BY parking_day, status, id

-- :name get-visitor-parkings :? :*
-- :doc retrieves a parking records given the day
SELECT id,email,status,slot_name,user_name,parking_day,on_behalf_of,parking_type FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day in (:v*:dates) and email = :email and status <> 'info'
ORDER BY parking_day, status, id

-- :name delete-parking! :! :n
-- :doc deletes a user record given the keys
DELETE FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_name = :parking_name and email = :email and status in ('pending','inactive')

-- :name unblock! :! :n
-- :doc deletes a user record given the keys
UPDATE parking
SET status = 'inactive'
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_name = :parking_name and email = :email and status = 'blocked'

-- :name get-taken-slots :? :*
-- :doc finds all taken slots for given day and parking
SELECT slot_name FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'active'

-- :name get-out-slots :? :*
-- :doc finds all taken slots for given day and parking
SELECT email FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'out'

-- :name create-out-of-office! :! :n
-- :doc creates a new parking request record
INSERT INTO parking AS S
(tenant_id, email, parking_day, parking_zone, parking_name, user_name, status, on_behalf_of, points)
VALUES (:tenant_id, :email, :parking_day, :parking_zone, :parking_name, :user_name, 'out', :on_behalf_of, -3)
ON CONFLICT (tenant_id, parking_zone, parking_name, parking_day, email)
    DO UPDATE SET status=excluded.status, email=excluded.email, user_name=excluded.user_name,on_behalf_of=excluded.on_behalf_of

-- :name cancel-out-of-office! :! :n
-- :doc deletes a user record given the keys
DELETE FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and email = :email and status = 'out'

-- :name has-out-slots? :? :*
-- :doc finds all taken slots for given day and parking
SELECT id,email,status,slot_name,user_name,parking_day,on_behalf_of FROM parking
WHERE tenant_id = :tenant_id and parking_day in (:v*:dates) and parking_zone = :parking_zone and parking_name = :parking_name and status = 'out' and email = :email

-- :name has-active-slot-with-name? :? :*
-- :doc finds all taken slots for given day and parking
SELECT id FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'active' and slot_name = :slot_name limit 1

-- :name get-taken-slots-for-emails :? :*
-- :doc finds all taken slots for given day and parking
SELECT slot_name,email,user_name FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and status = 'active' and email in (:v*:emails) and slot_name in (:v*:slot_names)

-- :name get-active-count-for-time :? :*
-- :doc gets analytics for a zone and parking
SELECT coalesce(sum(case status when 'active' then 1 else 0 end),0) as actives, coalesce(sum(case status when 'out' then 1 else 0 end), 0) as outs, coalesce(sum(case status when 'blocked' then 1 else 0 end), 0) as blockeds, coalesce(sum(case status when 'inactive' then 1 else 0 end), 0) as inactives, coalesce(sum(case status when 'pending' then 1 else 0 end), 0) as pendings, to_char(parking_day, 'YYYY-MM-DD') as parking_day FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_name = :parking_name and parking_day <= :to and parking_day >= :from and status in ('active','out','blocked','inactive','pending')
GROUP BY parking_day

-- :name get-score :? :*
-- :doc gets score for a zone
SELECT max(user_name) as user_name,email,sum(points) as points,count(email) as count,sum(case status when 'active' then 1 else 0 end) as actives,sum(case status when 'out' then 1 else 0 end) as outs,sum(case status when 'blocked' then 1 else 0 end) as blockeds,coalesce(sum(case status when 'inactive' then 1 else 0 end), 0) as inactives FROM parking
WHERE tenant_id = :tenant_id and parking_zone = :parking_zone and parking_day <= :to and parking_day >= :from
GROUP BY email
ORDER BY points DESC NULLS LAST, count DESC NULLS LAST

-- :name add-info! :! :n
INSERT INTO parking
(tenant_id, email, parking_day, parking_zone, parking_name, user_name, status, on_behalf_of, description)
VALUES (:tenant_id, :event_name, :parking_day, :parking_zone, :parking_name, :event_name, 'info', false, :description)

-- :name delete-info! :! :n
DELETE FROM parking
WHERE tenant_id = :tenant_id and parking_day = :parking_day and parking_zone = :parking_zone and parking_name = :parking_name and email = :event_name and status = 'info'

-- :name get-tenant-by-id :? :1
-- :doc loads a tenant by name
SELECT id,email,admin,host,jwt_valid_after
FROM tenant
WHERE id = :id

-- :name get-jwt-valid-after-from-tenant-by-id :? :1
-- :doc loads a tenant by name
SELECT jwt_valid_after
FROM tenant
WHERE id = :id

-- :name get-whole-tenant-by-id :? :1
-- :doc loads a tenant by name
SELECT *
FROM tenant
WHERE id = :id

-- :name get-tenant-by-host :? :1
-- :doc loads a tenant by name
SELECT id,email,admin,host,jwt_valid_after,activated
FROM tenant
WHERE host = :host

-- :name get-settings :? :1
-- :doc loads tenant's settings
SELECT settings
FROM tenant
WHERE id = :tenant_id

-- :name set-settings :! :1
-- :doc loads tenant's settings
UPDATE tenant SET settings = :settings, bang_seconds_utc = :bang_seconds_utc, admin = :admin
WHERE id = :tenant_id

-- :name update-jwt-valid-after :! :1
UPDATE tenant SET jwt_valid_after = :jwt_valid_after
WHERE id = :tenant_id

-- :name get-all-computable-tenants-id ?: :*
SELECT id
FROM tenant
WHERE bang_seconds_utc <= :bang_seconds_utc and (computed_date < :computed_date or computed_date is null)
ORDER BY bang_seconds_utc ASC

-- :name update-tenant-dates! :! :1
UPDATE tenant
SET computed_date = :computed_date, bang_seconds_utc = :bang_seconds_utc
WHERE id = :tenant_id

-- :name create-tenant! :! :1
INSERT INTO tenant (host, email, activation_token, settings)
VALUES(:host, :email, :activation_token, :settings)

-- :name activate-tenant! :! :1
UPDATE tenant SET activated = true WHERE host = :host and activation_token = :activation_token

-- :name create-login-token! :! :1
INSERT INTO email_token AS S
(host, email, token)
VALUES (:host, :email, :token)
ON CONFLICT (host, email)
    DO UPDATE SET token=excluded.token, created=now()

-- :name get-email-token :? :1
select * from email_token where host = :host and email = :email and token = :token and created >= TIMESTAMP 'yesterday'

-- :name delete-email-token! :! :1
delete from email_token where created < TIMESTAMP 'yesterday';
