-- qits-offline-core: the schema an @OfflineCapable service needs (qits-2148).
--
-- Copy this file into a Flyway migration of the service (its own V-number), and regenerate the
-- service's entity diagram. OfflineSchemaCheck fails boot, naming what is missing, when a service
-- has an @OfflineCapable bean and the database lacks either table or the function.

-- One row per (caller, Idempotency-Key): claimed by the write's own transaction before the method
-- runs, and holding the 2xx answer it replays. A failed write rolls its row back with it.
create table qits_idempotency (
    caller      text        not null,
    idem_key    text        not null,
    fingerprint text        not null,
    status      integer,
    body        text,
    etag        text,
    location    text,
    created_at  timestamptz not null default now(),
    primary key (caller, idem_key)
);
create index qits_idempotency_created_at on qits_idempotency (created_at);

-- The deletions a changed-since read reports, per resource name. Re-creating an id clears its row.
create table qits_tombstones (
    resource   text        not null,
    record_id  text        not null,
    version    bigint      not null,
    change_xid xid8        not null default pg_current_xact_id(),
    deleted_at timestamptz not null default now(),
    primary key (resource, record_id)
);
create index qits_tombstones_changes on qits_tombstones (resource, change_xid, record_id collate "C");
create index qits_tombstones_deleted_at on qits_tombstones (deleted_at);

-- Stamps the writing transaction's id on every UPDATE of an offline table. The id, never a
-- timestamp: a transaction that commits late still has an xid at or above the next reader's xmin.
create or replace function qits_touch_change_xid() returns trigger
language plpgsql as $$
begin
    new.change_xid := pg_current_xact_id();
    return new;
end
$$;

-- Each offline table then gets the column (an INSERT is stamped by the default) and the trigger.
-- In JPA the column is unmapped, or mapped insertable = false, updatable = false.
--
-- alter table things add column change_xid xid8 not null default pg_current_xact_id();
-- create index things_changes on things (change_xid, (id::text collate "C"));
-- create trigger things_change_xid before update on things
--     for each row execute function qits_touch_change_xid();
