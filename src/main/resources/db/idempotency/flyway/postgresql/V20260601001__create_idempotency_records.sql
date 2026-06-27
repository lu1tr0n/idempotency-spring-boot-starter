-- Idempotency starter — JDBC store schema (PostgreSQL), Flyway migration.
--
-- This file does NOT live on Flyway's default `classpath:db/migration` path,
-- so it never runs automatically. Opt in by adding this location:
--
--   spring.flyway.locations=classpath:db/migration,classpath:db/idempotency/flyway/postgresql
--
-- Alternatively (recommended), copy the DDL below into your own migration tree
-- as `db/migration/V<your-next-version>__create_idempotency_records.sql` so you
-- own the version number. Either way: a versioned migration runs exactly once
-- and is checksum-locked — never edit this file after it has been applied; ship
-- schema changes as a NEW migration.
--
-- No `IF NOT EXISTS`: a versioned migration must fail loud on a pre-existing or
-- mismatched object rather than silently no-op (which would mask schema drift).
-- The dev-only `spring.idempotency.jdbc.auto-create-table=true` path keeps its
-- own idempotent template; this is the production artifact.
--
-- Table name is hardcoded to the default `idempotency_records`. If you override
-- `spring.idempotency.jdbc.table-name`, rename it here to match.

CREATE TABLE idempotency_records (
    idempotency_key       VARCHAR(255) PRIMARY KEY,
    payload_hash          VARCHAR(64),
    http_status           INTEGER NOT NULL,
    response_headers      TEXT NOT NULL DEFAULT '{}',
    response_body         BYTEA NOT NULL,
    response_content_type VARCHAR(255),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    lock_token            VARCHAR(64),
    locked_until          TIMESTAMP WITH TIME ZONE
);

-- Speeds up the periodic expiry sweep. The starter does NOT auto-evict — run
-- `DELETE FROM idempotency_records WHERE expires_at < NOW()` on a schedule
-- (pg_cron, an application @Scheduled job), ideally batched on large tables.
CREATE INDEX idx_idempotency_records_expires_at
    ON idempotency_records (expires_at);
