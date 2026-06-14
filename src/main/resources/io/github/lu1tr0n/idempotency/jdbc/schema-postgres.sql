-- PostgreSQL schema for the idempotency starter's JDBC backend.
--
-- Designed to be created via Flyway / Liquibase as a regular migration.
-- The starter's `spring.idempotency.jdbc.auto-create-table=true` switch
-- runs the equivalent of this file at startup (intended for tests and
-- local development, not production).
--
-- Table name defaults to `idempotency_records` and can be overridden via
-- `spring.idempotency.jdbc.table-name`. If you rename, update this DDL
-- accordingly — the auto-create path substitutes the configured name into
-- a parameterised template at runtime.

CREATE TABLE IF NOT EXISTS idempotency_records (
    -- The validated idempotency key (255 chars max, see IdempotencyKey).
    -- Primary key is the natural choice — the table is keyed by exactly this.
    idempotency_key       VARCHAR(255)  PRIMARY KEY,

    -- Stripe-style payload validation. SHA-256 of (method + URI + body) as
    -- a 64-char hex string. Used to detect "same key, different request".
    -- NULL when payload validation was disabled at the time of capture.
    payload_hash          VARCHAR(64),

    -- Full HTTP response surface. Status code + headers + body + content
    -- type are stored so the replay is byte-for-byte equivalent to the
    -- original response. Headers are stored as a TEXT-encoded JSON object
    -- (not JSONB) so the same DDL works under H2 for tests and so the
    -- store implementation can bind plain string parameters without
    -- per-driver type-cast workarounds.
    http_status           INTEGER       NOT NULL,
    response_headers      TEXT          NOT NULL DEFAULT '{}',
    response_body         BYTEA         NOT NULL,
    response_content_type VARCHAR(255),

    -- Lifecycle timestamps. Both UTC.
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Lock state — when a row is INSERTed with NULL response columns, that
    -- row IS the lock. Once the protected operation completes and the
    -- response is filled in, the lock is implicitly released (any read
    -- sees the populated response and returns it instead of waiting).
    lock_token            VARCHAR(64),
    locked_until          TIMESTAMP WITH TIME ZONE
);

-- Background eviction is the consumer's job — Flyway-installed users
-- typically add a scheduled job (pg_cron, application-level @Scheduled)
-- that runs DELETE FROM idempotency_records WHERE expires_at < NOW()
-- every few hours. We do not auto-evict because we cannot assume the app
-- runs in a context where DELETE statements are appropriate.

-- Speeds up the periodic expiry sweep.
CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires_at
    ON idempotency_records (expires_at);
